package com.leakcanary.mcp.tools

import com.leakcanary.mcp.adb.AdbExecutor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Registers the get_device_memory tool on the MCP server.
 * Runs `adb shell dumpsys meminfo <package>` to get live memory stats.
 */
fun Server.registerDeviceMemoryTool() {
    addTool(
        name = "get_device_memory",
        description = "Get live memory statistics for an Android app from the device. " +
                "Use this tool when the user asks about: app memory usage, PSS, USS, heap allocation, " +
                "native heap, how much memory the app is using, memory pressure, memory footprint, " +
                "or wants broader memory context alongside leak data. " +
                "Runs 'adb shell dumpsys meminfo <package>' and returns parsed memory stats.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "The app package name (e.g. 'com.myapp.debug') (required)")
                })
                put("device_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ADB device serial for multi-device setups")
                })
            },
            required = listOf("package_name")
        )
    ) { request ->
        val packageName = request.arguments?.get("package_name")?.jsonPrimitive?.content
        if (packageName.isNullOrBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: 'package_name' parameter is required."))
            )
        }

        val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content

        val result = AdbExecutor.runShellCommand(deviceId, "dumpsys meminfo $packageName")
        if (result.isFailure) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error getting memory info: ${result.exceptionOrNull()?.message}"))
            )
        }

        val rawOutput = result.getOrDefault("")
        if (rawOutput.isBlank() || rawOutput.contains("No process found")) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No memory info found for '$packageName'. " +
                        "Ensure the app is currently running on the device."))
            )
        }

        val memInfo = parseMemInfo(rawOutput, packageName)
        val memInfoJson = json.encodeToString(memInfo)

        val header = buildString {
            appendLine("=== MEMORY INFO: $packageName ===")
            appendLine()
            if (memInfo.totalPss.isNotBlank()) appendLine("Total PSS:      ${memInfo.totalPss}")
            if (memInfo.totalRss.isNotBlank()) appendLine("Total RSS:      ${memInfo.totalRss}")
            if (memInfo.javaHeap.isNotBlank()) appendLine("Java Heap:      ${memInfo.javaHeap}")
            if (memInfo.nativeHeap.isNotBlank()) appendLine("Native Heap:    ${memInfo.nativeHeap}")
            if (memInfo.code.isNotBlank()) appendLine("Code:           ${memInfo.code}")
            if (memInfo.stack.isNotBlank()) appendLine("Stack:          ${memInfo.stack}")
            if (memInfo.graphics.isNotBlank()) appendLine("Graphics:       ${memInfo.graphics}")
            if (memInfo.system.isNotBlank()) appendLine("System:         ${memInfo.system}")
            appendLine()
            if (memInfo.objects.isNotEmpty()) {
                appendLine("--- Objects ---")
                for ((key, value) in memInfo.objects) {
                    appendLine("  $key: $value")
                }
                appendLine()
            }
            appendLine("--- Structured Data ---")
        }

        CallToolResult(content = listOf(TextContent(text = header + memInfoJson)))
    }
}

/**
 * Parses the raw output of `dumpsys meminfo <package>` into structured data.
 */
private fun parseMemInfo(rawOutput: String, packageName: String): MemoryInfo {
    val lines = rawOutput.lines()

    var totalPss = ""
    var totalRss = ""
    var javaHeap = ""
    var nativeHeap = ""
    var code = ""
    var stack = ""
    var graphics = ""
    var system = ""
    val objects = mutableMapOf<String, String>()

    var inAppSummary = false
    var inObjects = false

    for (line in lines) {
        val trimmed = line.trim()

        // App Summary section
        if (trimmed.startsWith("App Summary")) {
            inAppSummary = true
            inObjects = false
            continue
        }

        if (trimmed.startsWith("Objects")) {
            inObjects = true
            inAppSummary = false
            continue
        }

        if (inAppSummary) {
            val value = extractMemValue(trimmed)
            if (value != null) {
                when {
                    trimmed.contains("Java Heap:") -> javaHeap = value
                    trimmed.contains("Native Heap:") -> nativeHeap = value
                    trimmed.contains("Code:") -> code = value
                    trimmed.contains("Stack:") -> stack = value
                    trimmed.contains("Graphics:") -> graphics = value
                    trimmed.contains("System:") -> system = value
                    trimmed.contains("TOTAL PSS:") -> totalPss = value
                    trimmed.contains("TOTAL RSS:") -> totalRss = value
                    trimmed.contains("TOTAL:") && totalPss.isBlank() -> totalPss = value
                }
            }
        }

        if (inObjects) {
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val valStr = parts[1].trim()
                if (key.isNotBlank() && valStr.isNotBlank()) {
                    objects[key] = valStr
                }
            }
        }

        // Also catch TOTAL line outside App Summary
        if (!inAppSummary && trimmed.startsWith("TOTAL") && totalPss.isBlank()) {
            val value = extractTotalLine(trimmed)
            if (value != null) totalPss = value
        }
    }

    return MemoryInfo(
        packageName = packageName,
        totalPss = totalPss,
        totalRss = totalRss,
        javaHeap = javaHeap,
        nativeHeap = nativeHeap,
        code = code,
        stack = stack,
        graphics = graphics,
        system = system,
        objects = objects
    )
}

/**
 * Extracts a KB memory value from a line like "Java Heap:    12345"
 * and formats it as human-readable.
 */
private fun extractMemValue(line: String): String? {
    val colonIndex = line.lastIndexOf(':')
    if (colonIndex < 0) return null
    val valueStr = line.substring(colonIndex + 1).trim().split("\\s+".toRegex()).firstOrNull() ?: return null
    val kb = valueStr.replace(",", "").toLongOrNull() ?: return null
    return formatKb(kb)
}

/**
 * Extracts the PSS value from a TOTAL line like "TOTAL    12345    ..."
 */
private fun extractTotalLine(line: String): String? {
    val parts = line.split("\\s+".toRegex())
    if (parts.size < 2) return null
    val kb = parts[1].replace(",", "").toLongOrNull() ?: return null
    return formatKb(kb)
}

private fun formatKb(kb: Long): String {
    return when {
        kb >= 1024 * 1024 -> "%.1f GB (%,d KB)".format(kb / (1024.0 * 1024.0), kb)
        kb >= 1024 -> "%.1f MB (%,d KB)".format(kb / 1024.0, kb)
        else -> "$kb KB"
    }
}

@Serializable
private class MemoryInfo(
    val packageName: String = "",
    val totalPss: String = "",
    val totalRss: String = "",
    val javaHeap: String = "",
    val nativeHeap: String = "",
    val code: String = "",
    val stack: String = "",
    val graphics: String = "",
    val system: String = "",
    val objects: Map<String, String> = emptyMap()
)
