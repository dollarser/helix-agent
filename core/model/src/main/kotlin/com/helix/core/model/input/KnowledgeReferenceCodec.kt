package com.helix.core.model.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Canonical selected-reference representation shared by input producers and durable storage. */
object KnowledgeReferenceCodec {
    fun encode(items: List<KnowledgeReference>): String {
        require(items.size <= 8 && items.map { it.kind to it.resourceId }.distinct().size == items.size)
        return JsonArray(
            items.map { item ->
                buildJsonObject {
                    put("kind", item.kind)
                    put("resourceId", item.resourceId)
                    put("title", item.title)
                    put("sourceHash", item.sourceHash)
                    put("characters", item.characters)
                    put("completeness", item.completeness)
                }
            },
        ).toString()
    }

    fun decode(raw: String): List<KnowledgeReference> {
        require(raw.length <= 12000)
        val array = requireNotNull(Json.parseToJsonElement(raw) as? JsonArray)
        require(array.size <= 8)
        val result =
            array.map { element ->
                val item = requireNotNull(element as? JsonObject)
                require(item.keys == setOf("kind", "resourceId", "title", "sourceHash", "characters", "completeness"))

                fun text(key: String): String =
                    item
                        .getValue(key)
                        .jsonPrimitive
                        .also { require(it.isString) }
                        .content
                KnowledgeReference(
                    text("kind"),
                    text("resourceId"),
                    text("title"),
                    text("sourceHash"),
                    item
                        .getValue("characters")
                        .jsonPrimitive
                        .also { require(!it.isString) }
                        .int,
                    text("completeness"),
                )
            }
        encode(result)
        return result
    }
}
