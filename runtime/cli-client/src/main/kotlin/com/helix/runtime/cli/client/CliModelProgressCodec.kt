package com.helix.runtime.cli.client

import com.helix.core.model.ModelEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Separate preview format: empty is valid, terminal events are forbidden. */
object CliModelProgressCodec {
    fun encode(events: List<ModelEvent>): ByteArray {
        checkEvents(events)
        val bytes =
            buildJsonObject {
                put("version", 1)
                put("events", buildJsonArray { events.forEach { add(CliModelEventCodec.encodeEvent(it)) } })
            }.toString().toByteArray()
        return bytes
    }

    fun decode(bytes: ByteArray): List<ModelEvent> {
        require(bytes.isNotEmpty())
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        require(root.keys == setOf("version", "events") && root.getValue("version").jsonPrimitive.long == 1L)
        val rows = root.getValue("events").jsonArray
        return rows.map(CliModelEventCodec::decodeEvent).also(::checkEvents)
    }

    private fun checkEvents(events: List<ModelEvent>) {
        require(events.none { it is ModelEvent.Completed || it is ModelEvent.Refusal || it is ModelEvent.Error })
    }
}
