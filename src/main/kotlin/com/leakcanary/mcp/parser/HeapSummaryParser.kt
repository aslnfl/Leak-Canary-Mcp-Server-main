package com.leakcanary.mcp.parser

import com.leakcanary.mcp.model.HeapSummary

/**
 * Parses LeakCanary's METADATA section from raw logcat output.
 * The METADATA section contains heap-level statistics like class count,
 * instance count, heap size, bitmap stats, and device info.
 *
 * Example METADATA lines:
 *   METADATA
 *   Build.VERSION.SDK_INT: 33
 *   Build.MANUFACTURER: Google
 *   LeakCanary version: 2.12
 *   Stats: Class count: 12345 ...
 *   Analysis duration: 4500 ms
 */
object HeapSummaryParser {

    /**
     * Parses the heap summary from the raw logcat output.
     * Extracts values from the METADATA section using key-value line matching.
     *
     * @param rawOutput the raw unfiltered LeakCanary logcat output
     * @return parsed HeapSummary with available fields populated
     */
    fun parse(rawOutput: String): HeapSummary {
        val lines = rawOutput.lines().map { stripLogcatPrefix(it) }

        var sdkVersion = ""
        var classCount = ""
        var instanceCount = ""
        var objectArrayCount = ""
        var primitiveArrayCount = ""
        var threadCount = ""
        var heapTotalBytes = ""
        var bitmapCount = ""
        var bitmapTotalBytes = ""
        var largestBitmapSize = ""
        var deviceInfo = ""
        var androidVersion = ""
        var durationMs = ""

        var inMetadata = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.contains("METADATA") && !trimmed.contains("END")) {
                inMetadata = true
                continue
            }

            if (inMetadata && (trimmed.contains("====") || trimmed.contains("END METADATA"))) {
                inMetadata = false
                continue
            }

            if (!inMetadata && !trimmed.startsWith("Stats:")) {
                extractFromAnyLine(trimmed)?.let { (key, value) ->
                    when {
                        key.contains("LeakCanary version", ignoreCase = true) -> sdkVersion = value
                        key.contains("SDK_INT", ignoreCase = true) -> androidVersion = value
                        key.contains("MANUFACTURER", ignoreCase = true) || key.contains("MODEL", ignoreCase = true) -> {
                            deviceInfo = if (deviceInfo.isBlank()) value else "$deviceInfo $value"
                        }
                    }
                }
            }

            if (inMetadata || trimmed.startsWith("Stats:")) {
                extractFromAnyLine(trimmed)?.let { (key, value) ->
                    when {
                        key.contains("LeakCanary version", ignoreCase = true) -> sdkVersion = value
                        key.contains("SDK_INT", ignoreCase = true) -> androidVersion = value
                        key.contains("MANUFACTURER", ignoreCase = true) || key.contains("MODEL", ignoreCase = true) -> {
                            deviceInfo = if (deviceInfo.isBlank()) value else "$deviceInfo $value"
                        }
                        key.contains("Analysis duration", ignoreCase = true) -> durationMs = value
                    }
                }

                extractStatsValues(trimmed, "Class count")?.let { classCount = it }
                extractStatsValues(trimmed, "Instance count")?.let { instanceCount = it }
                extractStatsValues(trimmed, "Object array count")?.let { objectArrayCount = it }
                extractStatsValues(trimmed, "Primitive array count")?.let { primitiveArrayCount = it }
                extractStatsValues(trimmed, "Thread count")?.let { threadCount = it }
                extractStatsValues(trimmed, "Heap total bytes")?.let { heapTotalBytes = it }
                extractStatsValues(trimmed, "Bitmap count")?.let { bitmapCount = it }
                extractStatsValues(trimmed, "Bitmap total bytes")?.let { bitmapTotalBytes = it }
                extractStatsValues(trimmed, "Largest bitmap")?.let { largestBitmapSize = it }
            }
        }

        return HeapSummary(
            sdkVersion = sdkVersion,
            classCount = classCount,
            instanceCount = instanceCount,
            objectArrayCount = objectArrayCount,
            primitiveArrayCount = primitiveArrayCount,
            threadCount = threadCount,
            heapTotalBytes = heapTotalBytes,
            bitmapCount = bitmapCount,
            bitmapTotalBytes = bitmapTotalBytes,
            largestBitmapSize = largestBitmapSize,
            deviceInfo = deviceInfo,
            androidVersion = androidVersion,
            durationMs = durationMs
        )
    }

    /**
     * Extracts a key-value pair from a line containing a colon separator.
     */
    private fun extractFromAnyLine(line: String): Pair<String, String>? {
        val colonIndex = line.indexOf(':')
        if (colonIndex < 0) return null
        val key = line.substring(0, colonIndex).trim()
        val value = line.substring(colonIndex + 1).trim()
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }

    /**
     * Extracts a numeric value for a named stat from the Stats line.
     * Handles both "Key: Value" format and comma-separated "Key1: V1, Key2: V2" format.
     */
    private fun extractStatsValues(line: String, statName: String): String? {
        val regex = Regex("$statName\\s*:\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.get(1)
    }

    private fun stripLogcatPrefix(line: String): String {
        val leakCanaryIndex = line.indexOf("LeakCanary:")
        if (leakCanaryIndex >= 0) {
            return line.substring(leakCanaryIndex + "LeakCanary:".length).trimStart()
        }
        return line
    }
}
