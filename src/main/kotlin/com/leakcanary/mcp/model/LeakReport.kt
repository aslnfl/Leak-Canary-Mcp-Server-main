package com.leakcanary.mcp.model

import kotlinx.serialization.Serializable

/**
 * Complete leak report containing all traces, unreachable objects, and heap summary.
 * This is the top-level output of the detect_leaks tool.
 */
@Serializable
class LeakReport(
    val traces: List<LeakTrace> = emptyList(),
    val unreachableObjects: List<String> = emptyList(),
    val heapSummary: HeapSummary = HeapSummary(),
    val totalRetainedSize: String = "",
    val analysisTimestamp: String = ""
)
