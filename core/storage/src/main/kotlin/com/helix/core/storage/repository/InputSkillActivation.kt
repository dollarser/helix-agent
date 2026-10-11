package com.helix.core.storage.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Accepted host input metadata, not model arguments or an execution permission. */
data class InputSkillActivation(
    val source: String,
    val name: String,
    val snapshotHash: String,
    val arguments: String,
) {
    init {
        require(source in setOf("BUILT_IN", "USER_IMPORTED", "PROJECT"))
        require(name.isNotBlank() && name.length <= 64 && '\u0000' !in name)
        require(snapshotHash.length == 64 && snapshotHash.all { it in "0123456789abcdef" })
        require(arguments.length <= 4096 && '\u0000' !in arguments)
    }
}

object InputSkillActivations {
    fun encode(items: List<InputSkillActivation>): String {
        require(
            items.size <= 4 && items.map { Triple(it.source, it.name, it.snapshotHash) }.distinct().size == items.size,
        )
        val raw =
            JsonArray(
                items.map { item ->
                    buildJsonObject {
                        put("source", item.source)
                        put("name", item.name)
                        put("snapshotHash", item.snapshotHash)
                        put("arguments", item.arguments)
                    }
                },
            ).toString()
        require(raw.length <= 20000) { "SKILL_ACTIVATIONS_TOO_LARGE" }
        return raw
    }

    fun decode(raw: String): List<InputSkillActivation> {
        require(raw.length <= 20000)
        val array = requireNotNull(Json.parseToJsonElement(raw) as? JsonArray) { "SKILL_ACTIVATIONS_INVALID" }
        require(array.size <= 4)
        val items =
            array.map { element ->
                val item = requireNotNull(element as? JsonObject) { "SKILL_ACTIVATION_INVALID" }
                require(item.keys == setOf("source", "name", "snapshotHash", "arguments"))

                fun string(key: String): String =
                    item
                        .getValue(key)
                        .jsonPrimitive
                        .also { require(it.isString) }
                        .content
                InputSkillActivation(string("source"), string("name"), string("snapshotHash"), string("arguments"))
            }
        encode(items)
        return items
    }
}
