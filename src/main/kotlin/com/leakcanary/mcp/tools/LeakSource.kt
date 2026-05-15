package com.leakcanary.mcp.tools

import com.leakcanary.mcp.adb.AdbExecutor
import com.leakcanary.mcp.adb.AppStorageReader
import com.leakcanary.mcp.adb.StoredLeakResult
import com.leakcanary.mcp.model.HeapSummary
import com.leakcanary.mcp.model.LeakTrace
import com.leakcanary.mcp.parser.LeakTraceParser

/**
 * Result of fetching leak traces, including the source they came from.
 */
data class LeakFetchResult(
    val traces: List<LeakTrace>,
    val source: LeakDataSource,
    val rawLogcatOutput: String = "",
    val discoveredApps: List<DiscoveredApp> = emptyList(),
    val heapSummary: HeapSummary? = null
)

data class DiscoveredApp(
    val packageName: String,
    val leakCount: Int
)

enum class LeakDataSource {
    LOGCAT,
    APP_STORAGE
}

/**
 * Fetches leak traces using a three-tier strategy:
 * 1. Primary: App storage (persistent, full data via BLOB deserialization)
 * 2. Secondary: Logcat (real-time, has full reference chains from ASCII output)
 * 3. Auto-discovery: If no package_name, scan device for apps with LeakCanary
 *
 * @param deviceId optional device serial
 * @param packageName optional app package name
 * @return LeakFetchResult with traces and their source, or failure
 */
fun fetchLeakTraces(deviceId: String?, packageName: String?): Result<LeakFetchResult> {
    // Step 1: If package_name is provided, try app storage first (most reliable)
    if (!packageName.isNullOrBlank()) {
        val storageResult = AppStorageReader.readStoredLeaks(packageName, deviceId)
        if (storageResult.isSuccess) {
            val result = storageResult.getOrThrow()
            if (result.traces.isNotEmpty()) {
                return Result.success(LeakFetchResult(
                    traces = result.traces,
                    source = LeakDataSource.APP_STORAGE,
                    heapSummary = result.heapSummary
                ))
            }
        }
        if (storageResult.isFailure) {
            // Don't fail yet — try logcat as fallback
        }
    }

    // Step 2: Try logcat (works without package_name, but volatile)
    val logcatResult = AdbExecutor.captureLeakLogs(deviceId)
    if (logcatResult.isSuccess) {
        val rawOutput = logcatResult.getOrDefault("")
        if (rawOutput.isNotBlank()) {
            val traces = LeakTraceParser.parse(rawOutput)
            if (traces.isNotEmpty()) {
                return Result.success(LeakFetchResult(
                    traces = traces,
                    source = LeakDataSource.LOGCAT,
                    rawLogcatOutput = rawOutput
                ))
            }
        }
    }

    // Step 3: Auto-discover apps with LeakCanary when no package_name is provided
    if (packageName.isNullOrBlank()) {
        val leakCanaryApps = AppStorageReader.discoverLeakCanaryApps(deviceId)
        if (leakCanaryApps.isNotEmpty()) {
            if (leakCanaryApps.size == 1) {
                val singlePkg = leakCanaryApps.first()
                val storageResult = AppStorageReader.readStoredLeaks(singlePkg, deviceId)
                if (storageResult.isSuccess) {
                    val result = storageResult.getOrThrow()
                    if (result.traces.isNotEmpty()) {
                        return Result.success(LeakFetchResult(
                            traces = result.traces,
                            source = LeakDataSource.APP_STORAGE,
                            heapSummary = result.heapSummary
                        ))
                    }
                }
            } else {
                val discoveredApps = leakCanaryApps.map { pkg ->
                    val count = try {
                        AppStorageReader.readStoredLeaks(pkg, deviceId)
                            .getOrThrow().traces.size
                    } catch (_: Exception) { 0 }
                    DiscoveredApp(packageName = pkg, leakCount = count)
                }
                return Result.success(LeakFetchResult(
                    traces = emptyList(),
                    source = LeakDataSource.APP_STORAGE,
                    discoveredApps = discoveredApps
                ))
            }
        }
    }

    // Step 4: Nothing found anywhere
    return Result.success(LeakFetchResult(
        traces = emptyList(),
        source = LeakDataSource.LOGCAT,
        rawLogcatOutput = logcatResult.getOrDefault("")
    ))
}

/**
 * Builds a hint message when no leaks are found, suggesting the user provide
 * a package_name to read from app storage.
 */
fun buildNoLeaksMessage(packageName: String?): String {
    return if (packageName.isNullOrBlank()) {
        "No LeakCanary output found in logcat. The logcat buffer may have been cleared.\n\n" +
                "TIP: Provide the 'package_name' parameter (e.g., 'com.myapp.debug') to read stored leaks " +
                "directly from the app's LeakCanary database. This works even when the logcat buffer is empty."
    } else {
        "No leaks found in logcat or in the app's LeakCanary database for '$packageName'.\n" +
                "Ensure LeakCanary is integrated in the app and at least one leak has been detected."
    }
}

/**
 * Builds a response for when traces are empty but apps were discovered.
 * Returns null if no discovered apps are available (caller should use buildNoLeaksMessage instead).
 */
fun buildDiscoveredAppsPrompt(leakData: LeakFetchResult): String? {
    if (leakData.discoveredApps.isEmpty()) return null

    val appList = leakData.discoveredApps
        .sortedByDescending { it.leakCount }
        .mapIndexed { index, app ->
            "  ${index + 1}. ${app.packageName} (${app.leakCount} leak${if (app.leakCount != 1) "s" else ""})"
        }
        .joinToString("\n")

    return "No LeakCanary output found in logcat, but I found apps with LeakCanary databases on the device.\n\n" +
            "Please specify which app to analyze by providing the 'package_name' parameter:\n$appList\n\n" +
            "Example: use package_name=\"${leakData.discoveredApps.maxByOrNull { it.leakCount }?.packageName}\" to see its leaks."
}

/**
 * Builds a source indicator to append to tool responses,
 * so the user knows where the data came from.
 */
fun buildSourceNote(source: LeakDataSource): String {
    return when (source) {
        LeakDataSource.LOGCAT -> ""
        LeakDataSource.APP_STORAGE -> "\n[Data source: Read from app's stored LeakCanary database]"
    }
}
