package com.leakcanary.mcp.model

import kotlinx.serialization.Serializable

/**
 * A persisted record of a detected leak for tracking across sessions.
 */
@Serializable
class LeakHistoryEntry(
    val signature: String,
    val leakingClassName: String = "",
    val classification: String = "",
    val priority: String = "",
    val retainedSize: String = "",
    val gcRoot: String = "",
    val firstSeenTimestamp: String = "",
    val lastSeenTimestamp: String = "",
    val occurrenceCount: Int = 1,
    val status: String = "new"
)
