package com.leakcanary.mcp.tools

import com.leakcanary.mcp.analyzer.LeakAnalyzer
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
 * Registers the analyze_leak tool on the MCP server.
 * Deep-dive analysis of a specific leak trace by its signature hash.
 *
 * Uses a two-tier data source: logcat (primary) and app storage (fallback when logcat is empty).
 */
fun Server.registerAnalyzeLeakTool() {
    addTool(
        name = "analyze_leak",
        description = "Analyze a specific memory leak in detail by its signature hash. " +
                "Use this tool when the user asks to: analyze a leak, explain a leak, inspect a leak, " +
                "deep dive into a leak, understand why something is leaking, or get details about a specific leak signature. " +
                "Reads leak data from logcat first, and falls back to the app's stored LeakCanary database " +
                "if logcat is empty. " +
                "Returns full reference chain walkthrough, root cause classification, and severity assessment.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("signature", buildJsonObject {
                    put("type", "string")
                    put("description", "The leak signature hash (required)")
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
            required = listOf("signature")
        )
    ) { request ->
        val signature = request.arguments?.get("signature")?.jsonPrimitive?.content
        if (signature.isNullOrBlank()) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "Error: 'signature' parameter is required."))
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

        val allTraces = leakData.traces

        val traces = when (val filterResult = filterByPackageIfNeeded(allTraces, packageName, leakData.source, "analyze_leak")) {
            is PackageFilterResult.Ok -> filterResult.traces
            is PackageFilterResult.NoMatch -> return@addTool CallToolResult(
                content = listOf(TextContent(text = filterResult.message))
            )
            is PackageFilterResult.MultiApp -> return@addTool filterResult.prompt
        }

        val matchingTrace = traces.firstOrNull { it.signature == signature }

        if (matchingTrace == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent(text = "No leak trace found with signature '$signature'." +
                        buildSourceNote(leakData.source)))
            )
        }

        val analyzed = LeakAnalyzer.analyze(matchingTrace)

        val report = buildString {
            appendLine("=== LEAK ANALYSIS: ${analyzed.leakingClassName} ===")
            appendLine()
            appendLine("Signature: ${analyzed.signature}")
            appendLine("Classification: ${analyzed.classification}")
            appendLine("Priority: ${analyzed.priority}")
            appendLine("Retained: ${analyzed.retainedSize} in ${analyzed.retainedObjectCount} objects")
            appendLine()
            appendLine("--- GC Root ---")
            appendLine(analyzed.gcRoot)
            appendLine()
            appendLine("--- Reference Chain ---")
            for ((index, node) in analyzed.nodes.withIndex()) {
                val prefix = if (node.nodeType == "leaking_object") "╰→" else "├─"
                appendLine("  $prefix [${index}] ${node.className}")
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
            appendLine()
            appendLine("--- Structured Data ---")
            appendLine(json.encodeToString(analyzed))
            append(buildSourceNote(leakData.source))
        }

        CallToolResult(content = listOf(TextContent(text = report)))
    }
}
