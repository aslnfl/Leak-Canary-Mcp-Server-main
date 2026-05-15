package com.leakcanary.mcp

import com.leakcanary.mcp.tools.registerAnalyzeLeakTool
import com.leakcanary.mcp.tools.registerClearLeaksTool
import com.leakcanary.mcp.tools.registerDetectLeaksTool
import com.leakcanary.mcp.tools.registerDeviceMemoryTool
import com.leakcanary.mcp.tools.registerExportReportTool
import com.leakcanary.mcp.tools.registerHeapSummaryTool
import com.leakcanary.mcp.tools.registerLeakDiffTool
import com.leakcanary.mcp.tools.registerLeakHistoryTool
import com.leakcanary.mcp.tools.registerListDevicesTool
import com.leakcanary.mcp.tools.registerListLeaksTool
import com.leakcanary.mcp.tools.registerSearchLeakTool
import com.leakcanary.mcp.tools.registerSuggestFixesTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

private val SERVER_INSTRUCTIONS = """
    You are connected to the LeakCanary MCP Server for Android memory leak detection.
    
    WHEN TO USE THESE TOOLS:
    Use the leakcanary tools whenever the user asks about ANY of the following topics:
    - Memory leaks, leak detection, LeakCanary, leak analysis
    - "Check leaks", "find leaks", "detect leaks", "current leaks", "any leaks"
    - Memory issues, OOM, out of memory, retained objects, memory pressure
    - Activity leaks, Fragment leaks, ViewModel leaks, Context leaks
    - Heap analysis, heap dump, heap summary, memory stats
    - Leak history, past leaks, recurring leaks
    - How to fix a leak, fix suggestions, resolve memory leak
    
    TOOL SELECTION GUIDE:
    - detect_leaks: DEFAULT tool. Use this first when user asks about memory leaks.
    - list_leaks: Use when user asks "list all leaks", "show all leaks", "what are the current leaks", "how many leaks", "leak overview". Returns a compact summary.
    - search_leak: Use when user asks about a leak in a SPECIFIC class or component (e.g., "check leak in SearchHistoryManager", "is LoginActivity leaking", "find leak related to ViewModel").
    - get_heap_summary: Use when user asks about heap stats, memory usage, or device memory info.
    - get_leak_history: Use when user asks about past/previous/recurring leaks or leak trends.
    - analyze_leak: Use when user wants details about a specific leak (requires signature hash).
    - suggest_fixes: Use when user asks how to fix a specific leak (requires signature hash).
    - clear_leaks: Use when user wants to clear/reset leaks so the next scan only shows new ones.
    - list_devices: Use when user asks about connected devices/emulators or needs a device_id.
    - leak_diff: Use when user asks "what changed", "any new leaks", "fixed leaks", "regression check", or wants to compare current leaks against previous scans.
    - get_device_memory: Use when user asks about app memory usage, PSS, heap allocation, native memory, or memory footprint. Requires package_name.
    - export_report: Use when user asks to export, generate, save, or share a leak report. Creates a Markdown file.
    
    MULTI-APP SUPPORT:
    If the device has multiple apps with LeakCanary, the tools will detect this and ask the user
    which app to analyze. The user can specify the app via the 'package_name' parameter.
    When prompted with a list of detected apps, pass the chosen package to subsequent tool calls.

    TYPICAL WORKFLOW:
    1. Use list_devices to check connected devices if needed
    2. Start with list_leaks or detect_leaks to get all current leaks
    3. If multiple apps are detected, the tool will list them -- ask the user which app to focus on
    4. Use search_leak to filter leaks for a specific class or component
    5. Use analyze_leak for deep-dive on specific signatures from the report
    6. Use suggest_fixes to get actionable fix recommendations
    7. Use leak_diff to see what's new, recurring, or resolved compared to previous scans
    8. Use get_device_memory to check live memory stats for the app
    9. Use clear_leaks to reset the logcat buffer before the next test cycle
    10. Use export_report to generate a shareable Markdown report

    IMPORTANT: An Android device or emulator must be connected via adb for the tools to work.
""".trimIndent()

fun main(args: Array<String>) {
    System.err.println("[LeakCanary-MCP] Server starting...")
    
    val server = Server(
        serverInfo = Implementation(
            name = "leakcanary-mcp",
            version = "2.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        ),
        instructions = SERVER_INSTRUCTIONS
    )

    server.registerDetectLeaksTool()
    server.registerListLeaksTool()
    server.registerSearchLeakTool()
    server.registerHeapSummaryTool()
    server.registerLeakHistoryTool()
    server.registerAnalyzeLeakTool()
    server.registerSuggestFixesTool()
    server.registerClearLeaksTool()
    server.registerListDevicesTool()
    server.registerLeakDiffTool()
    server.registerDeviceMemoryTool()
    server.registerExportReportTool()

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    System.err.println("[LeakCanary-MCP] Transport initialized, starting session...")

    runBlocking {
        server.createSession(transport)
        val done = Job()
        server.onClose {
            System.err.println("[LeakCanary-MCP] Server closed")
            done.complete()
        }
        done.join()
    }
    System.err.println("[LeakCanary-MCP] Server exiting")
}
