package com.leakcanary.mcp.tools

import com.leakcanary.mcp.model.LeakTrace

/**
 * Deduplicates leak traces by signature.
 * When the same leak appears multiple times in a scan, groups them and keeps
 * one representative trace with an occurrence count.
 *
 * @param traces raw list of parsed traces (may contain duplicates)
 * @return deduplicated list with occurrence counts
 */
fun deduplicateTraces(traces: List<LeakTrace>): List<DeduplicatedLeak> {
    if (traces.isEmpty()) return emptyList()

    return traces
        .filter { it.signature.isNotBlank() }
        .groupBy { it.signature }
        .map { (_, group) ->
            // Pick the trace with the most nodes (richest data) as representative
            val representative = group.maxByOrNull { it.nodes.size } ?: group.first()
            DeduplicatedLeak(
                trace = representative,
                occurrenceCount = group.size
            )
        }
        .sortedByDescending { it.occurrenceCount }
}

/**
 * A leak trace with its occurrence count within a single scan.
 */
data class DeduplicatedLeak(
    val trace: LeakTrace,
    val occurrenceCount: Int
)
