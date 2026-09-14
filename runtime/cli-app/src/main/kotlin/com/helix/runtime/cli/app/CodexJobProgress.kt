package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelProgressCodec

/** Accessed under the owning runner's lock; never used as a durable completion record. */
internal class CodexJobProgress {
    private val events = ArrayList<ModelEvent>()

    fun clear() {
        events.clear()
    }

    fun append(chunk: List<ModelEvent>) {
        val preview =
            chunk.takeWhile {
                it !is ModelEvent.Completed && it !is ModelEvent.Refusal && it !is ModelEvent.Error
            }
        if (preview.isEmpty()) return
        events.addAll(preview)
    }

    fun read(offset: Int): List<ModelEvent> {
        require(offset in 0..events.size)
        return events.subList(offset, minOf(events.size, offset + 32)).toList()
    }
}
