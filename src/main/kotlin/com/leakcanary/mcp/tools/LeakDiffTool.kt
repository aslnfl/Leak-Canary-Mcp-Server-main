package com.leakcanary.mcp.tools

import com.leakcanary.mcp.analyzer.LeakAnalyzer
import com.leakcanary.mcp.history.LeakHistoryStore
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
 * Registers the leak_diff tool on the MCP server.
 * Compares current leaks against history to show new, recurring, and resolved leaks.
 */
fun Server.registerLeakDiffTool() {
    addTool(
        name = "leak_diff",
        description = "Compare current leaks against previous scans to show what changed. " +
                "Use this tool when the user asks: 'what changed', 'new leaks', 'fixed leaks', " +
                "'leak diff', 'regression check', 'compare leaks', 'any new leaks since last scan', " +
                "'resolved leaks', or 'leak status update'. " +
                "Returns a diff report showing NEW leaks (not seen before), RECURRING leaks " +
                "(seen again), and RESOLVED leaks (in history but not in current scan).",
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
        val allTraces = leakData.traces

        val traces = if (allTraces.isEmpty()) {
            emptyList()
        } else {
            when (val filterResult = filterByPackageIfNeeded(allTraces, packageName, leakData.source, "leak_diff")) {
                is PackageFilterResult.Ok -> filterResult.traces
                is PackageFilterResult.NoMatch -> return@addTool CallToolResult(
                    content = listOf(TextContent(text = filterResult.message))
                )
                is PackageFilterResult.MultiApp -> return@addTool filterResult.prompt
            }
        }

        val analyzedTraces = traces.map { LeakAnalyzer.analyze(it) }
        val currentSignatures = analyzedTraces.map { it.signature }.filter { it.isNotBlank() }.toSet()

        val history = LeakHistoryStore.loadAll()
        val historySignatures = history.map { it.signature }.toSet()

        val newLeaks = analyzedTraces.filter { it.signature.isNotBlank() && it.signature !in historySignatures }
        val recurringLeaks = analyzedTraces.filter { it.signature.isNotBlank() && it.signature in historySignatures }
        val resolvedLeaks = history.filter { it.signature !in currentSignatures }

        // Record current traces into history so the next diff is accurate
        LeakHistoryStore.record(analyzedTraces)

        val newSummaries = newLeaks.map { trace ->
            LeakDiffEntry(
                signature = trace.signature,
                leakingClassName = trace.leakingClassName,
                classification = trace.classification,
                priority = trace.priority,
                retainedSize = trace.retainedSize
            )
        }

        val recurringSummaries = recurringLeaks.map { trace ->
            val histEntry = history.firstOrNull { it.signature == trace.signature }
            LeakDiffEntry(
                signature = trace.signature,
                leakingClassName = trace.leakingClassName,
                classification = trace.classification,
                priority = trace.priority,
                retainedSize = trace.retainedSize,
                occurrenceCount = (histEntry?.occurrenceCount ?: 0) + 1,
                firstSeen = histEntry?.firstSeenTimestamp ?: ""
            )
        }

        val resolvedSummaries = resolvedLeaks.map { entry ->
            LeakDiffEntry(
                signature = entry.signature,
                leakingClassName = entry.leakingClassName,
                classification = entry.classification,
                priority = entry.priority,
                retainedSize = entry.retainedSize,
                occurrenceCount = entry.occurrenceCount,
                firstSeen = entry.firstSeenTimestamp,
                lastSeen = entry.lastSeenTimestamp
            )
        }

        val diffReport = LeakDiffReport(
            summary = DiffSummary(
                totalCurrent = analyzedTraces.size,
                newCount = newSummaries.size,
                recurringCount = recurringSummaries.size,
                resolvedCount = resolvedSummaries.size
            ),
            newLeaks = newSummaries,
            recurringLeaks = recurringSummaries,
            resolvedLeaks = resolvedSummaries
        )

        val reportJson = json.encodeToString(diffReport)

        val header = buildString {
            appendLine("=== LEAK DIFF REPORT ===")
            appendLine()
            appendLine("Current scan: ${analyzedTraces.size} leak(s)")
            appendLine("  NEW:       ${newSummaries.size} (not seen before)")
            appendLine("  RECURRING: ${recurringSummaries.size} (seen in previous scans)")
            appendLine("  RESOLVED:  ${resolvedSummaries.size} (in history but not in current scan)")
            appendLine()
        }

        val sourceNote = buildSourceNote(leakData.source)
        CallToolResult(content = listOf(TextContent(text = header + reportJson + sourceNote)))
    }
}

@Serializable
private class LeakDiffReport(
    val summary: DiffSummary,
    val newLeaks: List<LeakDiffEntry>,
    val recurringLeaks: List<LeakDiffEntry>,
    val resolvedLeaks: List<LeakDiffEntry>
)

@Serializable
private class DiffSummary(
    val totalCurrent: Int,
    val newCount: Int,
    val recurringCount: Int,
    val resolvedCount: Int
)

@Serializable
private class LeakDiffEntry(
    val signature: String,
    val leakingClassName: String = "",
    val classification: String = "",
    val priority: String = "",
    val retainedSize: String = "",
    val occurrenceCount: Int = 0,
    val firstSeen: String = "",
    val lastSeen: String = ""
)
