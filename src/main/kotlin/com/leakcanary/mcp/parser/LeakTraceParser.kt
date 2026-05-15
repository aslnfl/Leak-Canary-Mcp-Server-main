package com.leakcanary.mcp.parser

import com.leakcanary.mcp.model.LeakNode
import com.leakcanary.mcp.model.LeakStatus
import com.leakcanary.mcp.model.LeakTrace

/**
 * State-machine parser for LeakCanary logcat output.
 * Processes raw lines and extracts structured LeakTrace objects.
 *
 * Recognizes the LeakCanary ASCII box-drawing format:
 *   ┬───              -> trace start
 *   │ GC Root: ...    -> GC root line
 *   ├─ ClassName      -> chain node (object)
 *   │  ↓ ref.field    -> reference to next node
 *   │  Leaking: YES/NO/UNKNOWN (reason)
 *   │  ~~~            -> likely cause marker
 *   ╰→ ClassName      -> leaking object (final node)
 *   Signature: hex    -> trace signature
 *   Retaining X in Y  -> retained size
 */
object LeakTraceParser {

    private val SIGNATURE_REGEX = Regex("Signature:\\s*([a-fA-F0-9]+)")
    private val RETAINING_REGEX = Regex("Retaining\\s+([\\d,.]+)\\s*(B|bytes|KB|MB|GB)\\s+in\\s+([\\d,]+)\\s+objects?", RegexOption.IGNORE_CASE)
    private val LEAKING_REGEX = Regex("Leaking:\\s*(YES|NO|UNKNOWN)\\s*(?:\\((.*)\\))?")
    private val NODE_CLASS_REGEX = Regex("^[├╰│].*?([a-zA-Z][a-zA-Z0-9_.\\$]*(?:\\s+(?:instance|class|array))?)")
    private val REFERENCE_REGEX = Regex("↓\\s*(.*)")
    private val UNREACHABLE_REGEX = Regex("(\\d+)\\s+unreachable\\s+objects?", RegexOption.IGNORE_CASE)

    /**
     * Parses raw LeakCanary logcat output into a list of LeakTrace objects.
     *
     * @param rawOutput the raw logcat string (filtered or unfiltered)
     * @return list of parsed LeakTrace instances
     */
    fun parse(rawOutput: String): List<LeakTrace> {
        val lines = rawOutput.lines()
        val traces = mutableListOf<LeakTrace>()
        var i = 0

        while (i < lines.size) {
            val line = stripLogcatPrefix(lines[i])

            if (line.contains("┬───")) {
                val preSignature = scanBackwardsForSignature(lines, i)
                val preRetainedBytes = scanBackwardsForRetainedBytes(lines, i)
                val result = parseTraceBlock(lines, i, preSignature, preRetainedBytes)
                traces.add(result.trace)
                i = result.nextIndex
            } else {
                i++
            }
        }

        return traces
    }

    /**
     * Extracts unreachable object class names from the raw output.
     *
     * @param rawOutput the raw logcat string
     * @return list of unreachable object descriptions
     */
    fun parseUnreachableObjects(rawOutput: String): List<String> {
        val objects = mutableListOf<String>()
        val lines = rawOutput.lines()
        var inUnreachableSection = false

        for (rawLine in lines) {
            val line = stripLogcatPrefix(rawLine)

            if (UNREACHABLE_REGEX.containsMatchIn(line)) {
                inUnreachableSection = true
                continue
            }

            if (inUnreachableSection) {
                val trimmed = line.trim()
                if (trimmed.startsWith("├─") || trimmed.startsWith("╰→")) {
                    val className = trimmed.removePrefix("├─").removePrefix("╰→").trim()
                    if (className.isNotBlank()) {
                        objects.add(className)
                    }
                } else if (trimmed.contains("====") || trimmed.isBlank()) {
                    inUnreachableSection = false
                }
            }
        }

        return objects
    }

    private data class ParseResult(val trace: LeakTrace, val nextIndex: Int)

    /**
     * Scans up to 10 lines before the ┬─── marker for a Signature line.
     * In real LeakCanary output, Signature appears BEFORE the trace start.
     */
    private fun scanBackwardsForSignature(allLines: List<String>, traceStartIndex: Int): String {
        val lookback = 10
        val start = maxOf(0, traceStartIndex - lookback)
        for (j in (traceStartIndex - 1) downTo start) {
            val line = stripLogcatPrefix(allLines[j])
            SIGNATURE_REGEX.find(line)?.let { return it.groupValues[1] }
        }
        return ""
    }

    /**
     * Scans up to 10 lines before the ┬─── marker for the total retained bytes line.
     * Example: "47277133 bytes retained by leaking objects"
     */
    private fun scanBackwardsForRetainedBytes(allLines: List<String>, traceStartIndex: Int): String {
        val lookback = 10
        val start = maxOf(0, traceStartIndex - lookback)
        val retainedBytesRegex = Regex("([\\d,]+)\\s+bytes\\s+retained\\s+by\\s+leaking\\s+objects", RegexOption.IGNORE_CASE)
        for (j in (traceStartIndex - 1) downTo start) {
            val line = stripLogcatPrefix(allLines[j])
            retainedBytesRegex.find(line)?.let { return it.groupValues[1] }
        }
        return ""
    }

    private fun parseTraceBlock(
        allLines: List<String>,
        startIndex: Int,
        preSignature: String = "",
        preRetainedBytes: String = ""
    ): ParseResult {
        var gcRoot = ""
        val nodes = mutableListOf<NodeBuilder>()
        var signature = preSignature
        var retainedSize = ""
        var retainedObjectCount = ""
        var currentNode: NodeBuilder? = null
        var i = startIndex

        while (i < allLines.size) {
            val line = stripLogcatPrefix(allLines[i])
            val trimmed = line.trim()

            if (i > startIndex && trimmed.contains("┬───")) {
                break
            }

            if (trimmed.contains("GC Root:")) {
                gcRoot = trimmed.substringAfter("GC Root:").trim()
            }

            if (trimmed.startsWith("├─") || trimmed.startsWith("╰→")) {
                val isLeaking = trimmed.startsWith("╰→")
                val className = trimmed
                    .removePrefix("├─")
                    .removePrefix("╰→")
                    .trim()

                currentNode = NodeBuilder(className = className, isLeakingObject = isLeaking)
                nodes.add(currentNode)
            }

            REFERENCE_REGEX.find(trimmed)?.let { match ->
                currentNode?.referenceName = match.groupValues[1].trim()
            }

            LEAKING_REGEX.find(trimmed)?.let { match ->
                currentNode?.leakStatus = when (match.groupValues[1]) {
                    "YES" -> LeakStatus.YES
                    "NO" -> LeakStatus.NO
                    else -> LeakStatus.UNKNOWN
                }
                currentNode?.leakStatusReason = match.groupValues.getOrElse(2) { "" }
            }

            if (trimmed.contains("~~~")) {
                currentNode?.isLikelyCause = true
            }

            SIGNATURE_REGEX.find(trimmed)?.let { match ->
                signature = match.groupValues[1]
            }

            RETAINING_REGEX.find(trimmed)?.let { match ->
                retainedSize = "${match.groupValues[1]} ${match.groupValues[2]}"
                retainedObjectCount = match.groupValues[3]
            }

            if (trimmed.contains("====") && nodes.isNotEmpty()) {
                i++
                break
            }

            i++
        }

        if (retainedSize.isBlank() && preRetainedBytes.isNotBlank()) {
            val bytes = preRetainedBytes.replace(",", "").toLongOrNull() ?: 0L
            when {
                bytes >= 1024L * 1024L * 1024L -> retainedSize = "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024L * 1024L -> retainedSize = "%.1f MB".format(bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> retainedSize = "%.1f KB".format(bytes / 1024.0)
                else -> retainedSize = "$bytes B"
            }
        }

        val leakingClassName = nodes.lastOrNull()?.className?.split(" ")?.firstOrNull() ?: ""

        val builtNodes = nodes.map { builder ->
            LeakNode(
                className = builder.className,
                referenceName = builder.referenceName,
                leakStatus = builder.leakStatus,
                leakStatusReason = builder.leakStatusReason,
                isLikelyCause = builder.isLikelyCause,
                retainedSize = retainedSize,
                nodeType = if (builder.isLeakingObject) "leaking_object" else "reference"
            )
        }

        val trace = LeakTrace(
            signature = signature,
            gcRoot = gcRoot,
            nodes = builtNodes,
            retainedSize = retainedSize,
            retainedObjectCount = retainedObjectCount,
            leakingClassName = leakingClassName
        )

        return ParseResult(trace, i)
    }

    /**
     * Strips the standard adb logcat prefix (timestamp + tag) if present.
     * Example: "01-01 12:00:00.000 1234 1234 D LeakCanary: actual content"
     * becomes "actual content"
     */
    private fun stripLogcatPrefix(line: String): String {
        val leakCanaryIndex = line.indexOf("LeakCanary:")
        if (leakCanaryIndex >= 0) {
            return line.substring(leakCanaryIndex + "LeakCanary:".length).trimStart()
        }
        return line
    }

    private class NodeBuilder(
        val className: String,
        val isLeakingObject: Boolean = false,
        var referenceName: String = "",
        var leakStatus: LeakStatus = LeakStatus.UNKNOWN,
        var leakStatusReason: String = "",
        var isLikelyCause: Boolean = false
    )
}
