package com.leakcanary.mcp.adb

import com.leakcanary.mcp.model.HeapSummary
import com.leakcanary.mcp.model.LeakTrace
import com.leakcanary.mcp.model.LeakNode
import com.leakcanary.mcp.model.LeakStatus
import shark.HeapAnalysisSuccess
import shark.ApplicationLeak
import shark.LibraryLeak
import shark.LeakTraceObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.ObjectInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads LeakCanary's persisted leak data directly from the app's internal SQLite database.
 *
 * LeakCanary stores analysis results in `databases/leaks.db` (or `databases/leakcanary`) with:
 * - `heap_analysis.object`: Serialized `shark.HeapAnalysisSuccess` BLOB containing the FULL
 *   analysis — reference chains, retained sizes, leak status per node, GC roots, metadata.
 * - `leak`, `leak_trace`: Summary tables with signature, short_description, class_simple_name.
 *
 * Strategy:
 * 1. Pull the database to a temp file via `adb shell run-as <package> cat databases/<db>`
 * 2. Extract the serialized BLOBs and deserialize them using shark library
 * 3. Convert shark objects to our LeakTrace/LeakNode model
 * 4. Fall back to SQL-based queries if BLOB deserialization fails
 *
 * Requirements:
 * - The app must be debuggable (run-as works only on debug builds)
 * - A package_name to identify which app to read from
 */
object AppStorageReader {

    private val CANDIDATE_DB_NAMES = listOf("leaks.db", "leakcanary")

    /**
     * Discovers all debuggable apps on the device that have a LeakCanary database.
     * Lists third-party packages via `pm list packages -3`, then checks each with
     * `run-as <pkg> ls databases/` for the presence of leaks.db or leakcanary.
     *
     * @param deviceId optional device serial for multi-device setups
     * @return list of package names that have a LeakCanary database
     */
    fun discoverLeakCanaryApps(deviceId: String? = null): List<String> {
        val packagesResult = AdbExecutor.runShellCommand(deviceId, "pm list packages -3")
        if (packagesResult.isFailure) return emptyList()

        val packages = packagesResult.getOrDefault("")
            .lines()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }

        return packages.filter { pkg ->
            val dbListResult = AdbExecutor.runShellCommand(deviceId, "run-as $pkg ls databases/")
            if (dbListResult.isFailure) return@filter false
            val dbFiles = dbListResult.getOrDefault("")
            CANDIDATE_DB_NAMES.any { dbFiles.contains(it) }
        }
    }

    /**
     * Reads stored leak data from the app's LeakCanary database.
     * Uses BLOB deserialization (primary) for full reference chains and retained sizes,
     * with SQL-based fallback if deserialization fails.
     *
     * @param packageName the app's package name (e.g., "com.myapp.debug")
     * @param deviceId optional device serial for multi-device setups
     * @return StoredLeakResult with leak traces and optional heap summary, or failure
     */
    fun readStoredLeaks(packageName: String, deviceId: String? = null): Result<StoredLeakResult> {
        // Step 1: Find the database file
        val dbListResult = AdbExecutor.runShellCommand(deviceId, "run-as $packageName ls databases/")
        if (dbListResult.isFailure) {
            return Result.failure(RuntimeException(
                "Cannot access app storage for '$packageName'. " +
                        "Ensure the app is debuggable (debug build) and installed on the device."
            ))
        }

        val dbFiles = dbListResult.getOrDefault("")
        val dbName = CANDIDATE_DB_NAMES.firstOrNull { dbFiles.contains(it) }
            ?: return Result.failure(RuntimeException(
                "LeakCanary database not found in '$packageName'. " +
                        "Ensure the app has LeakCanary integrated and at least one leak has been detected. " +
                        "Files found: ${dbFiles.lines().filter { it.isNotBlank() }.joinToString(", ")}"
            ))

        // Step 2: Pull database to temp file
        val tempDb = pullDatabase(packageName, deviceId, dbName)
            ?: return Result.failure(RuntimeException(
                "Failed to pull LeakCanary database from '$packageName'. " +
                        "The database file may be empty or inaccessible."
            ))

        try {
            // Step 3: Try BLOB deserialization (full data)
            val blobResult = readFromBlobs(tempDb)
            if (blobResult != null && blobResult.traces.isNotEmpty()) {
                return Result.success(blobResult)
            }

            // Step 4: Fall back to SQL-based approach (limited data)
            return readFromSqlQueries(tempDb)
        } finally {
            tempDb.delete()
        }
    }

    /**
     * Extracts and deserializes HeapAnalysisSuccess BLOBs from the heap_analysis table.
     * Returns full leak traces with reference chains, retained sizes, and heap metadata.
     */
    private fun readFromBlobs(dbFile: File): StoredLeakResult? {
        try {
            // Extract BLOBs to temp files using sqlite3
            val countOutput = queryLocal(dbFile, "SELECT COUNT(*) FROM heap_analysis WHERE object IS NOT NULL AND leak_count > 0")
                ?: return null
            val count = countOutput.trim().toIntOrNull() ?: return null
            if (count == 0) return null

            val allTraces = mutableListOf<LeakTrace>()
            var latestHeapSummary: HeapSummary? = null

            // Process each heap analysis row
            val idsOutput = queryLocal(dbFile, "SELECT id FROM heap_analysis WHERE object IS NOT NULL AND leak_count > 0 ORDER BY created_at_time_millis DESC")
                ?: return null

            for (idStr in idsOutput.lines().filter { it.isNotBlank() }) {
                val id = idStr.trim().toIntOrNull() ?: continue
                val blobFile = extractBlob(dbFile, id) ?: continue

                try {
                    val analysis = deserializeBlob(blobFile) ?: continue

                    // Extract heap summary from the most recent analysis
                    if (latestHeapSummary == null) {
                        latestHeapSummary = extractHeapSummary(analysis)
                    }

                    // Convert application leaks
                    for (appLeak in analysis.applicationLeaks) {
                        for (trace in appLeak.leakTraces) {
                            allTraces.add(convertLeakTrace(trace, appLeak.signature, isLibraryLeak = false))
                        }
                    }

                    // Convert library leaks
                    for (libLeak in analysis.libraryLeaks) {
                        for (trace in libLeak.leakTraces) {
                            allTraces.add(convertLeakTrace(trace, libLeak.signature, isLibraryLeak = true))
                        }
                    }
                } finally {
                    blobFile.delete()
                }
            }

            return if (allTraces.isNotEmpty()) {
                StoredLeakResult(traces = allTraces, heapSummary = latestHeapSummary)
            } else null
        } catch (e: Exception) {
            // BLOB deserialization failed — caller will try SQL fallback
            return null
        }
    }

    /**
     * Extracts a single BLOB from heap_analysis to a temp file using sqlite3's writefile().
     */
    private fun extractBlob(dbFile: File, analysisId: Int): File? {
        val blobFile = File.createTempFile("leakcanary_blob_", ".bin")
        try {
            val query = "SELECT writefile('${blobFile.absolutePath}', object) FROM heap_analysis WHERE id=$analysisId"
            val result = queryLocal(dbFile, query)
            if (result == null || blobFile.length() < 100) {
                blobFile.delete()
                return null
            }
            return blobFile
        } catch (e: Exception) {
            blobFile.delete()
            return null
        }
    }

    /**
     * Deserializes a BLOB file into a HeapAnalysisSuccess object using Java Object Serialization.
     */
    private fun deserializeBlob(blobFile: File): HeapAnalysisSuccess? {
        return try {
            val bytes = blobFile.readBytes()
            val ois = ObjectInputStream(ByteArrayInputStream(bytes))
            val obj = ois.readObject()
            obj as? HeapAnalysisSuccess
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts a shark LeakTrace to our model LeakTrace with full reference chain.
     */
    private fun convertLeakTrace(
        sharkTrace: shark.LeakTrace,
        signature: String,
        isLibraryLeak: Boolean
    ): LeakTrace {
        val nodes = mutableListOf<LeakNode>()

        // Add reference chain nodes
        for ((index, ref) in sharkTrace.referencePath.withIndex()) {
            val origin = ref.originObject
            val isLikelyCause = (index == sharkTrace.referencePath.indexOfFirst {
                it.originObject.leakingStatus == LeakTraceObject.LeakingStatus.UNKNOWN
            })

            nodes.add(LeakNode(
                className = origin.className,
                referenceName = "${ref.owningClassSimpleName}.${ref.referenceDisplayName}",
                leakStatus = convertLeakingStatus(origin.leakingStatus),
                leakStatusReason = origin.leakingStatusReason,
                isLikelyCause = isLikelyCause,
                nodeType = ref.referenceType.name.lowercase()
            ))
        }

        // Add the leaking object as the final node
        val leakingObj = sharkTrace.leakingObject
        nodes.add(LeakNode(
            className = leakingObj.className,
            referenceName = "",
            leakStatus = convertLeakingStatus(leakingObj.leakingStatus),
            leakStatusReason = leakingObj.leakingStatusReason,
            isLikelyCause = false,
            nodeType = "leaking_object"
        ))

        val retainedBytes = sharkTrace.retainedHeapByteSize
        val retainedSize = if (retainedBytes != null) formatRetainedSize(retainedBytes) else ""
        val retainedCount = sharkTrace.retainedObjectCount?.toString() ?: ""

        return LeakTrace(
            signature = signature,
            gcRoot = sharkTrace.gcRootType.name,
            leakingClassName = leakingObj.classSimpleName,
            retainedSize = retainedSize,
            retainedObjectCount = retainedCount,
            classification = if (isLibraryLeak) "LIBRARY_LEAK" else "",
            nodes = nodes
        )
    }

    private fun convertLeakingStatus(status: LeakTraceObject.LeakingStatus): LeakStatus {
        return when (status) {
            LeakTraceObject.LeakingStatus.LEAKING -> LeakStatus.YES
            LeakTraceObject.LeakingStatus.NOT_LEAKING -> LeakStatus.NO
            LeakTraceObject.LeakingStatus.UNKNOWN -> LeakStatus.UNKNOWN
        }
    }

    private fun formatRetainedSize(bytes: Int): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * Extracts heap-level metadata from a HeapAnalysisSuccess into our HeapSummary model.
     */
    private fun extractHeapSummary(analysis: HeapAnalysisSuccess): HeapSummary {
        val meta = analysis.metadata
        return HeapSummary(
            classCount = meta["Class count"] ?: "",
            instanceCount = meta["Instance count"] ?: "",
            heapSize = meta["Heap total bytes"]?.toLongOrNull()?.let { formatRetainedSize(it.toInt()) } ?: "",
            bitmapCount = meta["Bitmap count"] ?: "",
            bitmapSize = meta["Bitmap total bytes"]?.toLongOrNull()?.let { formatRetainedSize(it.toInt()) } ?: "",
            deviceInfo = buildString {
                meta["Build.MANUFACTURER"]?.let { append("$it ") }
                meta["Build.VERSION.SDK_INT"]?.let { append("(API $it)") }
            }.trim(),
            appProcessName = meta["App process name"] ?: "",
            leakCanaryVersion = meta["LeakCanary version"] ?: ""
        )
    }

    // ==================== SQL Fallback ====================

    /**
     * Falls back to SQL-based queries when BLOB deserialization fails.
     * Returns limited data (no full reference chains or retained sizes).
     */
    private fun readFromSqlQueries(dbFile: File): Result<StoredLeakResult> {
        val tables = queryLocal(dbFile, ".tables")
        if (tables.isNullOrBlank()) {
            return Result.failure(RuntimeException("Could not read tables from LeakCanary database."))
        }

        val hasLeakTable = tables.contains("leak")
        val hasLeakTraceTable = tables.contains("leak_trace")

        val traces = if (hasLeakTable && hasLeakTraceTable) {
            readFromNewSchema(dbFile)
        } else if (tables.contains("heap_analysis")) {
            readFromLegacySchema(dbFile)
        } else {
            return Result.failure(RuntimeException(
                "Unrecognized LeakCanary database schema. Tables found: $tables"
            ))
        }

        return Result.success(StoredLeakResult(traces = traces))
    }

    private fun readFromNewSchema(dbFile: File): List<LeakTrace> {
        val query = """
            SELECT l.signature, l.short_description, l.is_library_leak,
                   lt.class_simple_name, ha.created_at_time_millis
            FROM leak l
            JOIN leak_trace lt ON lt.leak_id = l.id
            JOIN heap_analysis ha ON lt.heap_analysis_id = ha.id
            ORDER BY ha.created_at_time_millis DESC
        """.trimIndent().replace("\n", " ")

        val output = queryLocal(dbFile, query, headerMode = true)
        if (output.isNullOrBlank()) return emptyList()
        return parseNewSchemaOutput(output)
    }

    private fun readFromLegacySchema(dbFile: File): List<LeakTrace> {
        val query = """
            SELECT id, created_at_time_millis, leak_count, exception_summary
            FROM heap_analysis
            WHERE leak_count > 0
            ORDER BY created_at_time_millis DESC
        """.trimIndent().replace("\n", " ")

        val output = queryLocal(dbFile, query, headerMode = true)
        if (output.isNullOrBlank()) return emptyList()
        return parseLegacySchemaOutput(output)
    }

    private fun parseNewSchemaOutput(output: String): List<LeakTrace> {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 5) return@mapNotNull null

            val signature = parts[0].trim()
            val shortDescription = parts[1].trim()
            val isLibraryLeak = parts[2].trim() == "1"
            val classSimpleName = parts[3].trim()

            LeakTrace(
                signature = signature,
                leakingClassName = classSimpleName,
                classification = if (isLibraryLeak) "LIBRARY_LEAK" else "",
                nodes = listOf(
                    LeakNode(
                        className = classSimpleName,
                        leakStatus = LeakStatus.YES,
                        leakStatusReason = shortDescription,
                        nodeType = "leaking_object"
                    )
                )
            )
        }
    }

    private fun parseLegacySchemaOutput(output: String): List<LeakTrace> {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null

            val id = parts[0].trim()
            val leakCount = parts[2].trim()
            val summary = parts[3].trim()

            LeakTrace(
                signature = "heap-analysis-$id",
                leakingClassName = summary.ifBlank { "Unknown (legacy schema)" },
                retainedObjectCount = leakCount,
                nodes = listOf(
                    LeakNode(
                        className = summary.ifBlank { "HeapAnalysis #$id" },
                        leakStatus = LeakStatus.YES,
                        leakStatusReason = "$leakCount leak(s) detected",
                        nodeType = "leaking_object"
                    )
                )
            )
        }
    }

    // ==================== Database & ADB Helpers ====================

    /**
     * Pulls the database file from the app's sandbox to a local temp file.
     */
    private fun pullDatabase(packageName: String, deviceId: String?, dbName: String): File? {
        val tempFile = File.createTempFile("leakcanary_", ".db")
        try {
            val adbPath = resolveAdbPath()
            val command = mutableListOf<String>().apply {
                add(adbPath)
                if (!deviceId.isNullOrBlank()) {
                    add("-s")
                    add(deviceId)
                }
            add("exec-out")
            add("run-as")
            add(packageName)
            add("cat")
            add("databases/$dbName")
            }

            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()

            process.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                tempFile.delete()
                return null
            }

            if (tempFile.length() < 100) {
                tempFile.delete()
                return null
            }

            return tempFile
        } catch (e: Exception) {
            tempFile.delete()
            return null
        }
    }

    /**
     * Runs a sqlite3 query on a local database file using the host's sqlite3.
     */
    private fun queryLocal(dbFile: File, query: String, headerMode: Boolean = false): String? {
        try {
            val command = mutableListOf("sqlite3")
            if (headerMode) {
                command.add("-header")
                command.add("-separator")
                command.add("|")
            }
            command.add(dbFile.absolutePath)
            command.add(query)

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }

            return if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun resolveAdbPath(): String {
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val adbExe = if (isWindows) "adb.exe" else "adb"

        for (envVar in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val path = System.getenv(envVar)
            if (!path.isNullOrBlank()) {
                val candidate = File(path, "platform-tools/$adbExe")
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        return "adb"
    }
}

/**
 * Result from reading stored leaks, including optional heap summary when available
 * from BLOB deserialization.
 */
data class StoredLeakResult(
    val traces: List<LeakTrace>,
    val heapSummary: HeapSummary? = null
)
