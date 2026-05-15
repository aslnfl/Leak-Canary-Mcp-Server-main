package com.leakcanary.mcp.tools

import com.leakcanary.mcp.history.LeakHistoryStore
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
 * Registers the get_leak_history tool on the MCP server.
 * Queries persisted leak records across sessions.
 */
fun Server.registerLeakHistoryTool() {
    addTool(
        name = "get_leak_history",
        description = "Get memory leak history and past leak records. " +
                "Use this tool when the user asks about: leak history, past leaks, previous leaks, " +
                "recurring leaks, resolved leaks, leak trends, leak tracking, or when leaks were first seen. " +
                "Retrieves persisted leak records across sessions showing new, recurring, or resolved " +
                "leaks with timestamps and occurrence counts.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("signature", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional filter by exact leak signature hash")
                })
                put("since", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ISO date to filter entries seen after this time (e.g. 2026-01-01T00:00:00Z)")
                })
                put("limit", buildJsonObject {
                    put("type", "string")
                    put("description", "Maximum number of entries to return (default 50)")
                })
            }
        )
    ) { request ->
        val signature = request.arguments?.get("signature")?.jsonPrimitive?.content
        val since = request.arguments?.get("since")?.jsonPrimitive?.content
        val limit = request.arguments?.get("limit")?.jsonPrimitive?.content?.toIntOrNull() ?: 50

        val entries = LeakHistoryStore.query(signature = signature, since = since, limit = limit)

        if (entries.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No leak history found matching the given filters."))
            )
        }

        val historyJson = json.encodeToString(entries)
        CallToolResult(content = listOf(TextContent(text = historyJson)))
    }
}
