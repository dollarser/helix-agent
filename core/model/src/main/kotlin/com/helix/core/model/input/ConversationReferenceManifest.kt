package com.helix.core.model.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Fixed read boundary, not a transcript or authority supplied by the model. */
data class ConversationReferenceManifest(
    val throughSequence: Long,
    val revision: String,
    val messageCount: Int,
    val contentBytes: Long,
) {
    init {
        require(throughSequence in 0 until Long.MAX_VALUE && revision.matches(Regex("[a-f0-9]{64}")))
        require(messageCount > 0 && contentBytes > 0)
    }

    fun encode(): String =
        buildJsonObject {
            put("throughSequence", throughSequence)
            put("revision", revision)
            put("messageCount", messageCount)
            put("contentBytes", contentBytes)
        }.toString()

    companion object {
        fun parse(content: String): ConversationReferenceManifest {
            val fields =
                Json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
                    ?: throw IllegalArgumentException("REFERENCE_MANIFEST_INVALID")
            require(fields.keys == setOf("throughSequence", "revision", "messageCount", "contentBytes"))
            return ConversationReferenceManifest(
                fields.getValue("throughSequence").jsonPrimitive.long,
                fields.getValue("revision").jsonPrimitive.content,
                fields.getValue("messageCount").jsonPrimitive.int,
                fields.getValue("contentBytes").jsonPrimitive.long,
            )
        }
    }
}
