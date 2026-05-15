package com.leakcanary.mcp.model

import kotlinx.serialization.Serializable

/**
 * Represents a complete leak trace from GC root to the leaking object.
 * Contains the full reference chain, signature, and retained size.
 */
@Serializable
class LeakTrace(
    val signature: String = "",
    val gcRoot: String = "",
    val nodes: List<LeakNode> = emptyList(),
    val retainedSize: String = "",
    val retainedObjectCount: String = "",
    val leakingClassName: String = "",
    val classification: String = "",
    val priority: String = ""
)
