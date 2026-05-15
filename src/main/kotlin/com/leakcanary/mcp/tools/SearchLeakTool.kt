package com.leakcanary.mcp.tools

import com.leakcanary.mcp.adb.AdbExecutor
import com.leakcanary.mcp.analyzer.LeakAnalyzer
import com.leakcanary.mcp.history.LeakHistoryStore
import com.leakcanary.mcp.model.LeakReport
import com.leakcanary.mcp.model.LeakTrace
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
import java.time.Instant

private val json = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Registers the search_leak tool on the MCP server.
 * This tool captures all LeakCanary logs and filters traces
 * matching a user-provided class name or keyword.
 *
 * Uses a two-tier data source: logcat (primary) and app storage (fallback when logcat is empty).
 */
fun Server.registerSearchLeakTool() {
    addTool(
        name = "search_leak",
        description = "Search for a specific Android memory leak by class name or keyword. " +
                "Use this tool when the user asks about a leak in a specific class, component, or manager " +
                "(e.g., 'check memory leak in SearchHistoryManager', 'is there a leak in LoginActivity', " +
                "'find leak related to ViewModel', 'search leak AppUpdateUtil'). " +
                "Reads leak data from logcat first, and falls back to the app's stored LeakCanary database " +
                "if logcat is empty. " +
                "Filters traces matching the query against class names, " +
                "reference names, and GC roots, then returns a filtered structured report.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Class name or keyword to search for in leak traces (e.g. 'SearchHistoryManager', 'Activity', 'ViewModel')")
                })
                put("device_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ADB device serial for multi-device setups")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "App package name (e.g. 'com.myapp.debug'). Required to read stored leaks when logcat is empty. Also used to filter leaks when multiple apps are present.")
                })
            },
            required = listOf("query")
        )
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
        if (query.isNullOrBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: 'query' parameter is required. Provide a class name or keyword to search for."))
            )
        }

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

        val parsedTraces = leakData.traces

        val allTraces = when (val filterResult = filterByPackageIfNeeded(parsedTraces, packageName, leakData.source, "search_leak")) {
            is PackageFilterResult.Ok -> filterResult.traces
            is PackageFilterResult.NoMatch -> return@addTool CallToolResult(
                content = listOf(TextContent(text = filterResult.message))
            )
            is PackageFilterResult.MultiApp -> return@addTool filterResult.prompt
        }

        val matchingTraces = allTraces.filter { traceMatchesQuery(it, query) }

        if (matchingTraces.isEmpty()) {
            val availableClasses = allTraces.map { it.leakingClassName }.distinct().filter { it.isNotBlank() }
            val suggestion = if (availableClasses.isNotEmpty()) {
                "\n\nAvailable leaking classes:\n" + availableClasses.joinToString("\n") { "  - $it" }
            } else {
                ""
            }
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No leak traces found matching '$query'. " +
                        "Found ${allTraces.size} total leak(s) but none matched your search.$suggestion"))
            )
        }

        val analyzedTraces = matchingTraces.map { LeakAnalyzer.analyze(it) }

        val heapSummary = if (leakData.heapSummary != null) {
            leakData.heapSummary
        } else if (leakData.source == LeakDataSource.LOGCAT) {
            val fullLogsResult = AdbExecutor.captureFullLogs(deviceId)
            if (fullLogsResult.isSuccess) {
                HeapSummaryParser.parse(fullLogsResult.getOrDefault(""))
            } else {
                HeapSummaryParser.parse(leakData.rawLogcatOutput)
            }
        } else {
            HeapSummaryParser.parse("")
        }

        val report = LeakReport(
            traces = analyzedTraces,
            heapSummary = heapSummary,
            totalRetainedSize = computeTotalRetainedForSearch(analyzedTraces.map { it.retainedSize }),
            analysisTimestamp = Instant.now().toString()
        )

        LeakHistoryStore.record(analyzedTraces)

        val reportJson = json.encodeToString(report)
        val sourceNote = buildSourceNote(leakData.source)
        CallToolResult(content = listOf(TextContent(
            text = "Found ${analyzedTraces.size} leak(s) matching '$query' (out of ${allTraces.size} total):\n\n$reportJson$sourceNote"
        )))
    }
}

/**
 * Returns true if the trace contains the query string in any relevant field.
 * Match is case-insensitive.
 */
private fun traceMatchesQuery(trace: LeakTrace, query: String): Boolean {
    val lowerQuery = query.lowercase()

    if (trace.leakingClassName.lowercase().contains(lowerQuery)) return true
    if (trace.gcRoot.lowercase().contains(lowerQuery)) return true

    for (node in trace.nodes) {
        if (node.className.lowercase().contains(lowerQuery)) return true
        if (node.referenceName.lowercase().contains(lowerQuery)) return true
    }

    return false
}

private fun computeTotalRetainedForSearch(sizes: List<String>): String {
    var totalMb = 0.0
    for (size in sizes) {
        if (size.isBlank()) continue
        val parts = size.trim().split("\\s+".toRegex())
        if (parts.size < 2) continue
        val value = parts[0].replace(",", "").toDoubleOrNull() ?: continue
        totalMb += when (parts[1].uppercase()) {
            "GB" -> value * 1024.0
            "MB" -> value
            "KB" -> value / 1024.0
            "B", "BYTES" -> value / (1024.0 * 1024.0)
            else -> 0.0
        }
    }
    return "%.1f MB".format(totalMb)
}
