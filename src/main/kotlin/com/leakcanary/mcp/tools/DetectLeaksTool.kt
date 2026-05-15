package com.leakcanary.mcp.tools

import com.leakcanary.mcp.adb.AdbExecutor
import com.leakcanary.mcp.analyzer.LeakAnalyzer
import com.leakcanary.mcp.history.LeakHistoryStore
import com.leakcanary.mcp.model.LeakReport
import com.leakcanary.mcp.parser.HeapSummaryParser
import com.leakcanary.mcp.parser.LeakTraceParser
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
 * Registers the detect_leaks tool on the MCP server.
 * This tool captures LeakCanary logs from a connected device,
 * parses all leak traces, analyzes/prioritizes them, and returns a full structured report.
 *
 * Uses a two-tier data source: logcat (primary) and app storage (fallback when logcat is empty).
 */
fun Server.registerDetectLeaksTool() {
    addTool(
        name = "detect_leaks",
        description = "Detect Android memory leaks using LeakCanary. " +
                "Use this tool when the user asks about: memory leaks, leak detection, LeakCanary, " +
                "check leaks, find leaks, current leaks, leak analysis, memory issues, retained objects, " +
                "OOM, out of memory, activity leaks, fragment leaks, or anything related to Android memory leak detection. " +
                "Reads leak data from logcat first, and falls back to the app's stored LeakCanary database " +
                "if logcat is empty (e.g., after a reboot or buffer clear). " +
                "Classifies root causes (singleton, listener, context, library), assigns P0/P1/P2 priority, " +
                "and returns a structured report.",
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
                put("min_retained_bytes", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional minimum retained size filter (e.g. '5 MB')")
                })
            }
        )
    ) { request ->
        val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content
        val packageName = request.arguments?.get("package_name")?.jsonPrimitive?.content
        val minRetained = request.arguments?.get("min_retained_bytes")?.jsonPrimitive?.content

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

        val traces = when (val filterResult = filterByPackageIfNeeded(allTraces, packageName, leakData.source, "detect_leaks")) {
            is PackageFilterResult.Ok -> filterResult.traces
            is PackageFilterResult.NoMatch -> return@addTool CallToolResult(
                content = listOf(TextContent(text = filterResult.message))
            )
            is PackageFilterResult.MultiApp -> return@addTool filterResult.prompt
        }

        val analyzedTraces = traces.map { LeakAnalyzer.analyze(it) }

        val unreachable = if (leakData.source == LeakDataSource.LOGCAT && leakData.rawLogcatOutput.isNotBlank()) {
            LeakTraceParser.parseUnreachableObjects(leakData.rawLogcatOutput)
        } else {
            emptyList()
        }

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
            unreachableObjects = unreachable,
            heapSummary = heapSummary,
            totalRetainedSize = computeTotalRetained(analyzedTraces.map { it.retainedSize }),
            analysisTimestamp = Instant.now().toString()
        )

        LeakHistoryStore.record(analyzedTraces)

        val reportJson = json.encodeToString(report)
        val sourceNote = buildSourceNote(leakData.source)
        CallToolResult(content = listOf(TextContent(text = reportJson + sourceNote)))
    }
}

private fun computeTotalRetained(sizes: List<String>): String {
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
