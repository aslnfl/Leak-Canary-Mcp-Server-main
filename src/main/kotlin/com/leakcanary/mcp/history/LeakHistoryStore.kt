package com.leakcanary.mcp.history

import com.leakcanary.mcp.model.LeakHistoryEntry
import com.leakcanary.mcp.model.LeakTrace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Persists and retrieves leak history records to a local JSON file.
 * Storage location: ~/.leakcanary-mcp/history.json
 *
 * Each unique leak signature is tracked with first/last seen timestamps
 * and an occurrence count to identify recurring leaks.
 */
object LeakHistoryStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageDir: File by lazy {
        File(System.getProperty("user.home"), ".leakcanary-mcp").also { it.mkdirs() }
    }

    private val historyFile: File by lazy {
        File(storageDir, "history.json")
    }

    /**
     * Loads all history entries from disk.
     *
     * @return list of all stored LeakHistoryEntry objects, empty if file doesn't exist
     */
    fun loadAll(): List<LeakHistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val content = historyFile.readText()
            if (content.isBlank()) return emptyList()
            json.decodeFromString<List<LeakHistoryEntry>>(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Records detected leak traces into history.
     * Updates existing entries (bumps lastSeenTimestamp and occurrenceCount)
     * or creates new entries for newly seen signatures.
     *
     * @param traces the analyzed leak traces to record
     */
    fun record(traces: List<LeakTrace>) {
        val existing = loadAll().toMutableList()
        val now = Instant.now().toString()

        for (trace in traces) {
            if (trace.signature.isBlank()) continue

            val index = existing.indexOfFirst { it.signature == trace.signature }
            if (index >= 0) {
                val old = existing[index]
                existing[index] = LeakHistoryEntry(
                    signature = old.signature,
                    leakingClassName = trace.leakingClassName.ifBlank { old.leakingClassName },
                    classification = trace.classification.ifBlank { old.classification },
                    priority = trace.priority.ifBlank { old.priority },
                    retainedSize = trace.retainedSize.ifBlank { old.retainedSize },
                    gcRoot = trace.gcRoot.ifBlank { old.gcRoot },
                    firstSeenTimestamp = old.firstSeenTimestamp,
                    lastSeenTimestamp = now,
                    occurrenceCount = old.occurrenceCount + 1,
                    status = "recurring"
                )
            } else {
                existing.add(
                    LeakHistoryEntry(
                        signature = trace.signature,
                        leakingClassName = trace.leakingClassName,
                        classification = trace.classification,
                        priority = trace.priority,
                        retainedSize = trace.retainedSize,
                        gcRoot = trace.gcRoot,
                        firstSeenTimestamp = now,
                        lastSeenTimestamp = now,
                        occurrenceCount = 1,
                        status = "new"
                    )
                )
            }
        }

        save(existing)
    }

    /**
     * Queries history with optional filters.
     *
     * @param signature optional filter by exact signature
     * @param since optional ISO timestamp to filter entries seen after this time
     * @param limit maximum number of entries to return
     * @return filtered and limited list of history entries
     */
    fun query(signature: String? = null, since: String? = null, limit: Int = 50): List<LeakHistoryEntry> {
        var entries = loadAll()

        if (!signature.isNullOrBlank()) {
            entries = entries.filter { it.signature == signature }
        }

        if (!since.isNullOrBlank()) {
            try {
                val sinceInstant = Instant.parse(since)
                entries = entries.filter {
                    try {
                        Instant.parse(it.lastSeenTimestamp).isAfter(sinceInstant)
                    } catch (e: Exception) {
                        true
                    }
                }
            } catch (_: Exception) {
                // ignore invalid date format
            }
        }

        return entries.take(limit)
    }

    private fun save(entries: List<LeakHistoryEntry>) {
        try {
            historyFile.writeText(json.encodeToString(entries))
        } catch (_: Exception) {
            // silently fail on write errors
        }
    }
}
