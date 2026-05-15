package com.leakcanary.mcp.analyzer

import com.leakcanary.mcp.model.LeakTrace

/**
 * Classifies leak traces by root cause pattern and assigns priority levels.
 *
 * Classification heuristics:
 * - "static.*Instance" in chain -> Singleton leak
 * - "Listener|Callback|Observer" in chain -> Listener leak
 * - "context|mContext" field holding Activity -> Context leak
 * - Third-party package prefix (com.google, com.facebook, etc.) -> Library leak
 *
 * Priority levels based on retained size:
 * - P0: retained > 20 MB
 * - P1: retained > 5 MB
 * - P2: everything else
 */
object LeakAnalyzer {

    private val THIRD_PARTY_PREFIXES = listOf(
        "com.google.",
        "com.facebook.",
        "com.crashlytics.",
        "com.squareup.",
        "com.bumptech.glide.",
        "io.reactivex.",
        "com.airbnb.",
        "com.appsflyer.",
        "com.moengage.",
        "com.clevertap.",
        "com.adjust."
    )

    /**
     * Analyzes a leak trace and returns a copy with classification and priority populated.
     *
     * @param trace the parsed leak trace
     * @return new LeakTrace with classification and priority fields set
     */
    fun analyze(trace: LeakTrace): LeakTrace {
        val classification = classify(trace)
        val priority = assignPriority(trace)

        return LeakTrace(
            signature = trace.signature,
            gcRoot = trace.gcRoot,
            nodes = trace.nodes,
            retainedSize = trace.retainedSize,
            retainedObjectCount = trace.retainedObjectCount,
            leakingClassName = trace.leakingClassName,
            classification = classification,
            priority = priority
        )
    }

    /**
     * Classifies the leak based on patterns found in the reference chain.
     */
    private fun classify(trace: LeakTrace): String {
        val allText = buildChainText(trace)

        if (isLibraryLeak(trace)) return "LIBRARY_LEAK"
        if (isSingletonLeak(allText)) return "SINGLETON_LEAK"
        if (isListenerLeak(allText)) return "LISTENER_LEAK"
        if (isContextLeak(allText)) return "CONTEXT_LEAK"
        if (isViewModelLeak(allText)) return "VIEWMODEL_LEAK"
        if (isHandlerLeak(allText)) return "HANDLER_LEAK"

        return "UNKNOWN"
    }

    /**
     * Assigns priority based on retained size.
     * Falls back to P2 if size cannot be parsed.
     */
    private fun assignPriority(trace: LeakTrace): String {
        val sizeInMb = parseRetainedMb(trace.retainedSize)
        return when {
            sizeInMb > 20.0 -> "P0"
            sizeInMb > 5.0 -> "P1"
            else -> "P2"
        }
    }

    private fun isSingletonLeak(chainText: String): Boolean {
        return chainText.contains("static", ignoreCase = true) &&
                (chainText.contains("Instance", ignoreCase = false) ||
                        chainText.contains("mInstance", ignoreCase = false) ||
                        chainText.contains("INSTANCE", ignoreCase = false) ||
                        chainText.contains("singleton", ignoreCase = true))
    }

    private fun isListenerLeak(chainText: String): Boolean {
        val listenerPatterns = listOf("Listener", "Callback", "Observer", "listener", "callback", "observer")
        return listenerPatterns.any { chainText.contains(it) }
    }

    private fun isContextLeak(chainText: String): Boolean {
        val contextPatterns = listOf("mContext", "context", "applicationContext")
        return contextPatterns.any { chainText.contains(it, ignoreCase = false) } &&
                chainText.contains("Activity", ignoreCase = false)
    }

    private fun isLibraryLeak(trace: LeakTrace): Boolean {
        return trace.nodes.any { node ->
            THIRD_PARTY_PREFIXES.any { prefix ->
                node.className.contains(prefix, ignoreCase = true)
            }
        }
    }

    private fun isViewModelLeak(chainText: String): Boolean {
        return chainText.contains("ViewModel", ignoreCase = false)
    }

    private fun isHandlerLeak(chainText: String): Boolean {
        return chainText.contains("Handler", ignoreCase = false) &&
                chainText.contains("Message", ignoreCase = false)
    }

    private fun buildChainText(trace: LeakTrace): String {
        val builder = StringBuilder()
        builder.append(trace.gcRoot).append(" ")
        for (node in trace.nodes) {
            builder.append(node.className).append(" ")
            builder.append(node.referenceName).append(" ")
        }
        return builder.toString()
    }

    /**
     * Parses a retained size string like "47.3 MB" or "4500 KB" into megabytes.
     */
    private fun parseRetainedMb(retainedSize: String): Double {
        if (retainedSize.isBlank()) return 0.0
        val parts = retainedSize.trim().split("\\s+".toRegex())
        if (parts.size < 2) return 0.0

        val value = parts[0].replace(",", "").toDoubleOrNull() ?: return 0.0
        return when (parts[1].uppercase()) {
            "GB" -> value * 1024.0
            "MB" -> value
            "KB" -> value / 1024.0
            "B", "BYTES" -> value / (1024.0 * 1024.0)
            else -> 0.0
        }
    }
}
