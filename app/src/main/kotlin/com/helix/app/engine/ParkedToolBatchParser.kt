package com.helix.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ParkedToolCallRef(
    val wireId: String,
    val localId: String,
    val name: String,
    val arguments: String,
)

/** Strict parser for the exact persisted ASSISTANT/TOOL_CALLS batch being reconciled. */
internal object ParkedToolBatchParser {
    fun parse(content: String): List<ParkedToolCallRef> {
        val array = Json.parseToJsonElement(content) as? JsonArray ?: error("PARKED_BATCH_MALFORMED: not an array")
        require(array.isNotEmpty()) { "PARKED_BATCH_MALFORMED: empty batch" }
        val calls = array.map(::parseCall)
        require(calls.map { it.wireId }.distinct().size == calls.size) {
            "PARKED_BATCH_MALFORMED: duplicate wire id"
        }
        require(calls.map { it.localId }.distinct().size == calls.size) {
            "PARKED_BATCH_MALFORMED: duplicate local id"
        }
        return calls
    }

    private fun parseCall(element: kotlinx.serialization.json.JsonElement): ParkedToolCallRef {
        val obj = element as? JsonObject ?: error("PARKED_BATCH_MALFORMED: call is not an object")
        return ParkedToolCallRef(
            wireId = obj.requiredString("id"),
            localId = obj.requiredString("localId"),
            name = obj.requiredString("name"),
            arguments = obj.requiredString("arguments").ifBlank { "{}" },
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: error("PARKED_BATCH_MALFORMED: missing $key")
}
