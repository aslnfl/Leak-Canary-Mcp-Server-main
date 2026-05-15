package com.leakcanary.mcp.model

import kotlinx.serialization.Serializable

/**
 * Represents a single node in a LeakCanary reference chain.
 * Each node is an object on the path from GC root to the leaking object.
 */
@Serializable
class LeakNode(
    val className: String,
    val referenceName: String = "",
    val leakStatus: LeakStatus = LeakStatus.UNKNOWN,
    val leakStatusReason: String = "",
    val isLikelyCause: Boolean = false,
    val retainedSize: String = "",
    val nodeType: String = ""
)

@Serializable
enum class LeakStatus {
    YES,
    NO,
    UNKNOWN
}
