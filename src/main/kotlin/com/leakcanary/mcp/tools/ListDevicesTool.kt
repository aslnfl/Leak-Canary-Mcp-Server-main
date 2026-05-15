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

private val json = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Registers the list_devices tool on the MCP server.
 * Lists all connected Android devices and emulators via adb.
 */
fun Server.registerListDevicesTool() {
    addTool(
        name = "list_devices",
        description = "List all connected Android devices and emulators. " +
                "Use this tool when the user asks: 'what devices are connected', 'list devices', " +
                "'show emulators', 'which device', 'adb devices', or before using device_id on other tools. " +
                "Returns device serial IDs and their connection state.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {}
        )
    ) { _ ->
        val result = AdbExecutor.listDevices()
        if (result.isFailure) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error listing devices: ${result.exceptionOrNull()?.message}"))
            )
        }

        val rawOutput = result.getOrDefault("")
        val devices = parseDeviceList(rawOutput)

        if (devices.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No Android devices or emulators connected. " +
                        "Connect a device via USB or start an emulator, then try again."))
            )
        }

        val response = DeviceListResult(
            totalCount = devices.size,
            devices = devices
        )

        CallToolResult(content = listOf(TextContent(text = json.encodeToString(response))))
    }
}

/**
 * Parses the raw output of `adb devices` into structured device entries.
 * Skips the header line ("List of devices attached") and empty lines.
 */
private fun parseDeviceList(rawOutput: String): List<DeviceInfo> {
    return rawOutput.lines()
        .filter { it.contains("\t") }
        .mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size >= 2) {
                DeviceInfo(
                    deviceId = parts[0].trim(),
                    state = parts[1].trim()
                )
            } else null
        }
}

@Serializable
private class DeviceListResult(
    val totalCount: Int,
    val devices: List<DeviceInfo>
)

@Serializable
private class DeviceInfo(
    val deviceId: String,
    val state: String
)
