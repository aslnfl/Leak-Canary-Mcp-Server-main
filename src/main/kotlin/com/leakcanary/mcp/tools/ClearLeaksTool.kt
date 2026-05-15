package com.leakcanary.mcp.tools

import com.leakcanary.mcp.adb.AdbExecutor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Registers the clear_leaks tool on the MCP server.
 * Clears the logcat buffer so the next scan only shows new leaks.
 */
fun Server.registerClearLeaksTool() {
    addTool(
        name = "clear_leaks",
        description = "Clear the LeakCanary logcat buffer on the connected device. " +
                "Use this tool when the user asks to: clear leaks, reset leaks, start fresh, " +
                "clear logcat, remove old leaks, or wants to only see new leaks on the next scan. " +
                "After clearing, the next detect_leaks or list_leaks call will only show newly detected leaks.",
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

        val result = AdbExecutor.clearLogcat(deviceId)
        if (result.isFailure) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error clearing logcat: ${result.exceptionOrNull()?.message}"))
            )
        }

        CallToolResult(
            content = listOf(
                TextContent(
                    text = "Logcat buffer cleared successfully. The next leak scan will only show newly detected leaks."
                )
            )
        )
    }
}
