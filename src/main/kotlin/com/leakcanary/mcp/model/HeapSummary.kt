package com.leakcanary.mcp.model

import kotlinx.serialization.Serializable

/**
 * Heap-level statistics from the LeakCanary METADATA section or deserialized BLOB.
 */
@Serializable
class HeapSummary(
    val sdkVersion: String = "",
    val classCount: String = "",
    val instanceCount: String = "",
    val objectArrayCount: String = "",
    val primitiveArrayCount: String = "",
    val threadCount: String = "",
    val heapTotalBytes: String = "",
    val heapSize: String = "",
    val bitmapCount: String = "",
    val bitmapTotalBytes: String = "",
    val bitmapSize: String = "",
    val largestBitmapSize: String = "",
    val deviceInfo: String = "",
    val androidVersion: String = "",
    val durationMs: String = "",
    val appProcessName: String = "",
    val leakCanaryVersion: String = ""
)
