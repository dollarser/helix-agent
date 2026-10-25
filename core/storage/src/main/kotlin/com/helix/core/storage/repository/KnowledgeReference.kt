package com.helix.core.storage.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** User-selected library content. Neither content nor its provenance grants tool access. */
data class KnowledgeReference(
    val kind: String,
    val resourceId: String,
    val title: String,
    val sourceHash: String,
    val range: String,
    val text: String,
    val keepVersion: Boolean = false,
    val completeness: String = "USER_CONTENT",
) {
    init {
        require(completeness.length in 1..40 && completeness.all { it in 'A'..'Z' || it == '_' })
        require(kind in setOf("NOTE", "KNOWLEDGE", "SOURCE"))
        require(
            resourceId.isNotBlank() && resourceId.length <= 100 &&
                resourceId.all { it.isLetterOrDigit() || it in "_-" },
        )
        require(title.isNotBlank() && title.length <= 160 && '\u0000' !in title)
        require(sourceHash.length == 64 && sourceHash.all { it in "0123456789abcdef" })
        require(range in setOf("FULL", "EXCERPT"))
        require(text.isNotBlank() && text.length <= 24000 && '\u0000' !in text)
    }
}

object KnowledgeReferences {
    fun encode(items: List<KnowledgeReference>): String {
        require(items.size <= 8 && items.sumOf { it.text.length } <= 24000)
        require(items.map { it.kind to it.resourceId }.distinct().size == items.size)
        return JsonArray(
            items.map { item ->
                buildJsonObject {
                    put("kind", item.kind)
                    put("resourceId", item.resourceId)
                    put("title", item.title)
                    put("sourceHash", item.sourceHash)
                    put("range", item.range)
                    put("text", item.text)
                    put("keepVersion", item.keepVersion)
                    put("completeness", item.completeness)
                }
            },
        ).toString()
    }

    fun decode(raw: String): List<KnowledgeReference> {
        require(raw.length <= 200000)
        val array = requireNotNull(Json.parseToJsonElement(raw) as? JsonArray)
        require(array.size <= 8)
        val result =
            array.map { element ->
                val item = requireNotNull(element as? JsonObject)
                require(
                    item.keys ==
                        setOf(
                            "kind",
                            "resourceId",
                            "title",
                            "sourceHash",
                            "range",
                            "text",
                            "keepVersion",
                            "completeness",
                        ),
                )

                fun string(key: String) =
                    item
                        .getValue(key)
                        .jsonPrimitive
                        .also { require(it.isString) }
                        .content
                KnowledgeReference(
                    string("kind"),
                    string("resourceId"),
                    string("title"),
                    string("sourceHash"),
                    string("range"),
                    string("text"),
                    item
                        .getValue("keepVersion")
                        .jsonPrimitive
                        .also { require(!it.isString) }
                        .boolean,
                    string("completeness"),
                )
            }
        encode(result)
        return result
    }

    fun authoredPrefix(
        text: String,
        items: List<KnowledgeReference>,
    ): String = if (items.isEmpty()) text else text.removeSuffix(message("", items))

    /** JSON is quoted data inside an explicit trust boundary, never a new instruction section. */
    fun message(
        text: String,
        items: List<KnowledgeReference>,
    ): String =
        if (items.isEmpty()) {
            text
        } else {
            "$text\n\n[UNTRUSTED_KNOWLEDGE_REFERENCES]\n" +
                "${encode(items)}\n[/UNTRUSTED_KNOWLEDGE_REFERENCES]"
        }
}
