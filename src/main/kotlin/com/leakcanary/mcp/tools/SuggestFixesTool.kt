package com.leakcanary.mcp.tools

import com.leakcanary.mcp.analyzer.FixSuggester
import com.leakcanary.mcp.analyzer.LeakAnalyzer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Registers the suggest_fixes tool on the MCP server.
 * Generates actionable fix suggestions for a given leak by its signature.
 *
 * Uses a two-tier data source: logcat (primary) and app storage (fallback when logcat is empty).
 */
fun Server.registerSuggestFixesTool() {
    addTool(
        name = "suggest_fixes",
        description = "Get fix suggestions for a memory leak by its signature hash. " +
                "Use this tool when the user asks: how to fix a leak, fix suggestions, resolve a leak, " +
                "patch a memory leak, stop a leak, or get recommendations for a specific leak. " +
                "Reads leak data from logcat first, and falls back to the app's stored LeakCanary database " +
                "if logcat is empty. " +
                "Returns actionable fix steps based on the leak pattern " +
                "(singleton, listener, context, library, ViewModel, Handler, etc.).",
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

        val traces = when (val filterResult = filterByPackageIfNeeded(allTraces, packageName, leakData.source, "suggest_fixes")) {
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
        val fixes = FixSuggester.suggest(analyzed)

        val output = buildString {
            appendLine("=== FIX SUGGESTIONS: ${analyzed.leakingClassName} ===")
            appendLine()
            appendLine("Signature: ${analyzed.signature}")
            appendLine("Classification: ${analyzed.classification}")
            appendLine("Priority: ${analyzed.priority}")
            appendLine("Retained: ${analyzed.retainedSize}")
            appendLine()
            for (line in fixes) {
                appendLine(line)
            }
            append(buildSourceNote(leakData.source))
        }

        CallToolResult(content = listOf(TextContent(text = output)))
    }
}
