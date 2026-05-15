package com.leakcanary.mcp.tools

import com.leakcanary.mcp.adb.AdbExecutor
import com.leakcanary.mcp.parser.HeapSummaryParser
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val json = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Registers the get_heap_summary tool on the MCP server.
 * Extracts heap-level statistics from the LeakCanary METADATA section.
 */
fun Server.registerHeapSummaryTool() {
    addTool(
        name = "get_heap_summary",
        description = "Get Android heap memory summary and statistics from LeakCanary. " +
                "Use this tool when the user asks about: heap stats, heap summary, memory stats, " +
                "heap size, memory usage, bitmap count, class count, instance count, heap dump info, " +
                "or device memory statistics. " +
                "Extracts heap-level data from the latest LeakCanary dump including class count, " +
                "instance count, heap size, bitmap stats, and device info.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("device_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ADB device serial for multi-device setups")
                })
            }
        )
    ) { request ->
        val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content

        val result = AdbExecutor.captureFullLogs(deviceId)
        if (result.isFailure) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error capturing logs: ${result.exceptionOrNull()?.message}"))
            )
        }

        val rawOutput = result.getOrDefault("")
        if (rawOutput.isBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No LeakCanary output found in logcat."))
            )
        }

        val summary = HeapSummaryParser.parse(rawOutput)
        val summaryJson = json.encodeToString(summary)
        CallToolResult(content = listOf(TextContent(text = summaryJson)))
    }
}
