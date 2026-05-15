package com.leakcanary.mcp.model

import kotlinx.serialization.Serializable

/**
 * Compact summary of a single memory leak.
 * Used by the list_leaks tool for a quick overview without full reference chains.
 */
@Serializable
class LeakSummary(
    val leakingClassName: String = "",
    val signature: String = "",
    val classification: String = "",
    val priority: String = "",
    val retainedSize: String = "",
    val gcRoot: String = ""
)
