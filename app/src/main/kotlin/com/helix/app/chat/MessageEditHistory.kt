package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Presentation ranges identify old replies, never an execution outcome or authorization. */
internal object MessageEditHistory {
    const val KIND = "MESSAGE_EDIT_HISTORY"
    const val MAX_BYTES = 8 * 1024 * 1024

    fun read(
        storage: HelixStorage,
        sessionId: String,
    ): List<LongRange> {
        val row = storage.messages.latestOfKind(sessionId, KIND) ?: return emptyList()
        return decode(requireNotNull(storage.messages.readContentBounded(row, MAX_BYTES)))
    }

    fun encode(ranges: List<LongRange>): String =
        buildJsonArray {
            ranges.forEach { range ->
                add(
                    buildJsonArray {
                        add(range.first)
                        add(range.last)
                    },
                )
            }
        }.toString()

    fun decode(text: String): List<LongRange> =
        Json.parseToJsonElement(text).jsonArray.map { value ->
            val pair = value.jsonArray
            require(pair.size == 2)
            val start = pair[0].jsonPrimitive.long
            val end = pair[1].jsonPrimitive.long
            require(start >= 0 && end >= start)
            start..end
        }

    fun merge(
        ranges: List<LongRange>,
        added: LongRange,
    ): List<LongRange> {
        val sorted = (ranges + listOf(added)).sortedBy { it.first }
        return sorted.fold(emptyList<LongRange>()) { result, next ->
            val last = result.lastOrNull()
            if (last != null &&
                next.first <= last.last
            ) {
                result.dropLast(1) + listOf(last.first..maxOf(last.last, next.last))
            } else {
                result + listOf(next)
            }
        }
    }

    fun remap(
        ranges: List<LongRange>,
        sequences: Map<Long, Long>,
    ): List<LongRange> =
        ranges.mapNotNull { range ->
            val selected = sequences.filterKeys { it in range }.values
            if (selected.isEmpty()) null else selected.min()..selected.max()
        }

    fun copyToBranch(
        storage: HelixStorage,
        source: String,
        target: String,
        sequences: Map<Long, Long>,
    ) {
        val ranges = remap(read(storage, source), sequences)
        if (ranges.isNotEmpty()) {
            storage.messages.append(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                target,
                null,
                "SYSTEM",
                KIND,
                encode(ranges),
            )
        }
    }
}
