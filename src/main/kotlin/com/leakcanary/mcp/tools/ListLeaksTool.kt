package com.leakcanary.mcp.tools

import com.leakcanary.mcp.analyzer.LeakAnalyzer
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
 * Registers the list_leaks tool on the MCP server.
 * This tool returns a compact summary of all detected memory leaks
 * without the full reference chains, giving a quick overview.
 *
 * Uses a two-tier data source: logcat (primary) and app storage (fallback when logcat is empty).
 */
fun Server.registerListLeaksTool() {
    addTool(
        name = "list_leaks",
        description = "List all Android memory leaks detected by LeakCanary in a compact summary format. " +
                "Use this tool when the user asks: 'list all leaks', 'show all leaks', 'what are the current leaks', " +
                "'how many leaks', 'leak overview', 'leak summary', 'all memory leaks', 'show me the leaks'. " +
                "Reads leak data from logcat first, and falls back to the app's stored LeakCanary database " +
                "if logcat is empty (e.g., after a reboot or buffer clear). " +
                "Returns a concise list with leaking class, signature, classification, priority, and retained size " +
                "for each leak. Use this for a quick overview before diving into specific leaks with analyze_leak or search_leak.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("device_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ADB device serial for multi-device setups")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "App package name (e.g. 'com.myapp.debug'). Required to read stored leaks when logcat is empty. Also used to filter leaks when multiple apps are present.")
                })
            }
        )
    ) { request ->
        val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content
        val packageName = request.arguments?.get("package_name")?.jsonPrimitive?.content

        val fetchResult = fetchLeakTraces(deviceId, packageName)
        if (fetchResult.isFailure) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: ${fetchResult.exceptionOrNull()?.message}"))
            )
        }

        val leakData = fetchResult.getOrThrow()
        if (leakData.traces.isEmpty()) {
            val prompt = buildDiscoveredAppsPrompt(leakData) ?: buildNoLeaksMessage(packageName)
            return@addTool CallToolResult(
                content = listOf(TextContent(text = prompt))
            )
        }

        val allTraces = leakData.traces

        val traces = when (val filterResult = filterByPackageIfNeeded(allTraces, packageName, leakData.source, "list_leaks")) {
            is PackageFilterResult.Ok -> filterResult.traces
            is PackageFilterResult.NoMatch -> return@addTool CallToolResult(
                content = listOf(TextContent(text = filterResult.message))
            )
            is PackageFilterResult.MultiApp -> return@addTool filterResult.prompt
        }

        val deduplicated = deduplicateTraces(traces)
        val summaries = deduplicated.map { dedup ->
            val analyzed = LeakAnalyzer.analyze(dedup.trace)
            LeakSummaryWithCount(
                leakingClassName = analyzed.leakingClassName,
                signature = analyzed.signature,
                classification = analyzed.classification,
                priority = analyzed.priority,
                retainedSize = analyzed.retainedSize,
                gcRoot = analyzed.gcRoot,
                occurrenceCount = dedup.occurrenceCount
            )
        }

        val result = LeakListResult(
            uniqueCount = summaries.size,
            totalOccurrences = traces.size,
            leaks = summaries
        )

        val resultJson = json.encodeToString(result)
        val sourceNote = buildSourceNote(leakData.source)
        CallToolResult(content = listOf(TextContent(text = resultJson + sourceNote)))
    }
}

@Serializable
private class LeakListResult(
    val uniqueCount: Int = 0,
    val totalOccurrences: Int = 0,
    val leaks: List<LeakSummaryWithCount> = emptyList()
)

@Serializable
private class LeakSummaryWithCount(
    val leakingClassName: String = "",
    val signature: String = "",
    val classification: String = "",
    val priority: String = "",
    val retainedSize: String = "",
    val gcRoot: String = "",
    val occurrenceCount: Int = 1
)
