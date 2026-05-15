package com.leakcanary.mcp.tools

import com.leakcanary.mcp.analyzer.LeakAnalyzer
import com.leakcanary.mcp.history.LeakHistoryStore
import com.leakcanary.mcp.model.LeakTrace
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Registers the export_report tool on the MCP server.
 * Generates a Markdown leak report file for sharing with the team.
 */
fun Server.registerExportReportTool() {
    addTool(
        name = "export_report",
        description = "Export a Markdown leak report file for sharing with your team. " +
                "Use this tool when the user asks to: export leaks, generate report, create report, " +
                "share leak report, save leak data, write report, or export to markdown/file. " +
                "Fetches current leaks, analyzes them, and writes a formatted Markdown report " +
                "with all traces, classifications, priorities, fix suggestions, and history data.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("device_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ADB device serial for multi-device setups")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "App package name (e.g. 'com.myapp.debug'). Required to read stored leaks when logcat is empty.")
                })
                put("output_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file path for the report (defaults to ~/.leakcanary-mcp/reports/leak-report-<timestamp>.md)")
                })
            }
        )
    ) { request ->
        val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content
        val packageName = request.arguments?.get("package_name")?.jsonPrimitive?.content
        val outputPath = request.arguments?.get("output_path")?.jsonPrimitive?.content

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

        val traces = when (val filterResult = filterByPackageIfNeeded(leakData.traces, packageName, leakData.source, "export_report")) {
            is PackageFilterResult.Ok -> filterResult.traces
            is PackageFilterResult.NoMatch -> return@addTool CallToolResult(
                content = listOf(TextContent(text = filterResult.message))
            )
            is PackageFilterResult.MultiApp -> return@addTool filterResult.prompt
        }

        if (traces.isEmpty()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No leak traces to export."))
            )
        }

        val deduplicated = deduplicateTraces(traces)
        val analyzedLeaks = deduplicated.map { dedup ->
            AnalyzedDedup(
                analyzed = LeakAnalyzer.analyze(dedup.trace),
                occurrenceCount = dedup.occurrenceCount
            )
        }

        val history = LeakHistoryStore.loadAll()
        val markdown = generateMarkdownReport(analyzedLeaks, history, packageName, leakData.source)

        val reportFile = resolveOutputFile(outputPath)
        try {
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(markdown)
        } catch (e: Exception) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error writing report file: ${e.message}"))
            )
        }

        CallToolResult(
            content = listOf(
                TextContent(
                    text = "Leak report exported successfully!\n\n" +
                            "File: ${reportFile.absolutePath}\n" +
                            "Leaks: ${analyzedLeaks.size} unique (${traces.size} total occurrences)\n" +
                            "Format: Markdown\n\n" +
                            "You can share this file with your team or attach it to a Jira ticket."
                )
            )
        )
    }
}

private class AnalyzedDedup(
    val analyzed: LeakTrace,
    val occurrenceCount: Int
)

private fun resolveOutputFile(outputPath: String?): File {
    if (!outputPath.isNullOrBlank()) return File(outputPath)

    val reportsDir = File(System.getProperty("user.home"), ".leakcanary-mcp/reports")
    val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    return File(reportsDir, "leak-report-$timestamp.md")
}

private fun generateMarkdownReport(
    leaks: List<AnalyzedDedup>,
    history: List<com.leakcanary.mcp.model.LeakHistoryEntry>,
    packageName: String?,
    source: LeakDataSource
): String {
    val now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())

    val p0Leaks = leaks.filter { it.analyzed.priority == "P0" }
    val p1Leaks = leaks.filter { it.analyzed.priority == "P1" }
    val p2Leaks = leaks.filter { it.analyzed.priority == "P2" || it.analyzed.priority.isBlank() }

    return buildString {
        appendLine("# LeakCanary Leak Report")
        appendLine()
        appendLine("**Generated:** $now")
        if (!packageName.isNullOrBlank()) appendLine("**Package:** `$packageName`")
        appendLine("**Data source:** ${if (source == LeakDataSource.APP_STORAGE) "App storage (database)" else "Logcat"}")
        appendLine("**Total unique leaks:** ${leaks.size}")
        appendLine()

        // Summary table
        appendLine("## Summary")
        appendLine()
        appendLine("| Priority | Count | Description |")
        appendLine("|----------|-------|-------------|")
        appendLine("| **P0** (Critical, >20MB) | ${p0Leaks.size} | ${if (p0Leaks.isEmpty()) "None" else p0Leaks.joinToString(", ") { it.analyzed.leakingClassName }} |")
        appendLine("| **P1** (High, >5MB) | ${p1Leaks.size} | ${if (p1Leaks.isEmpty()) "None" else p1Leaks.joinToString(", ") { it.analyzed.leakingClassName }} |")
        appendLine("| **P2** (Medium) | ${p2Leaks.size} | ${if (p2Leaks.isEmpty()) "None" else p2Leaks.joinToString(", ") { it.analyzed.leakingClassName }} |")
        appendLine()

        // Detailed leaks
        appendLine("## Leak Details")
        appendLine()

        for ((index, dedup) in leaks.withIndex()) {
            val leak = dedup.analyzed
            val histEntry = history.firstOrNull { it.signature == leak.signature }

            appendLine("### ${index + 1}. ${leak.leakingClassName}")
            appendLine()
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| **Signature** | `${leak.signature}` |")
            appendLine("| **Classification** | ${leak.classification.ifBlank { "UNKNOWN" }} |")
            appendLine("| **Priority** | ${leak.priority.ifBlank { "P2" }} |")
            appendLine("| **Retained Size** | ${leak.retainedSize.ifBlank { "Unknown" }} |")
            if (dedup.occurrenceCount > 1) {
                appendLine("| **Occurrences (this scan)** | ${dedup.occurrenceCount} |")
            }
            if (leak.gcRoot.isNotBlank()) {
                appendLine("| **GC Root** | ${leak.gcRoot} |")
            }
            if (histEntry != null) {
                appendLine("| **First Seen** | ${histEntry.firstSeenTimestamp} |")
                appendLine("| **Last Seen** | ${histEntry.lastSeenTimestamp} |")
                appendLine("| **Total Occurrences** | ${histEntry.occurrenceCount} |")
                appendLine("| **Status** | ${histEntry.status} |")
            }
            appendLine()

            // Reference chain
            if (leak.nodes.isNotEmpty()) {
                appendLine("**Reference Chain:**")
                appendLine()
                appendLine("```")
                for ((nodeIndex, node) in leak.nodes.withIndex()) {
                    val prefix = if (node.nodeType == "leaking_object") "╰→" else "├─"
                    appendLine("  $prefix [$nodeIndex] ${node.className}")
                    if (node.referenceName.isNotBlank()) {
                        appendLine("       ↓ ${node.referenceName}")
                    }
                    if (node.leakStatus.name != "UNKNOWN") {
                        appendLine("       Leaking: ${node.leakStatus} (${node.leakStatusReason})")
                    }
                    if (node.isLikelyCause) {
                        appendLine("       ~~~ LIKELY CAUSE ~~~")
                    }
                }
                appendLine("```")
                appendLine()
            }

            appendLine("---")
            appendLine()
        }

        // History summary
        if (history.isNotEmpty()) {
            appendLine("## Leak History")
            appendLine()
            appendLine("| Class | Signature | Status | Occurrences | First Seen |")
            appendLine("|-------|-----------|--------|-------------|------------|")
            for (entry in history.sortedByDescending { it.occurrenceCount }.take(20)) {
                appendLine("| ${entry.leakingClassName} | `${entry.signature.take(12)}...` | ${entry.status} | ${entry.occurrenceCount} | ${entry.firstSeenTimestamp.take(10)} |")
            }
            appendLine()
        }

        appendLine("---")
        appendLine("*Generated by LeakCanary MCP Server*")
    }
}
