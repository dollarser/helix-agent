package com.helix.core.storage.knowledge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Open Markdown body with a single JSON metadata comment; no second authoritative body. */
@Suppress("TooManyFunctions") // Encode/decode pairs for the three shapes share one document envelope.
internal object KnowledgeDocumentCodec {
    private const val PREFIX = "<!-- helix-document: "

    data class Document(
        val metadata: JsonObject,
        val body: String,
    )

    fun encode(
        metadata: JsonObject,
        body: String,
    ): String = "$PREFIX${metadata.toString().replace("<", "\\u003c").replace(">", "\\u003e")} -->\n$body"

    fun decode(text: String): Document {
        val line = text.substringBefore('\n')
        require(line.startsWith(PREFIX) && line.endsWith(" -->")) { "Invalid knowledge document metadata" }
        val metadata = Json.parseToJsonElement(line.removePrefix(PREFIX).removeSuffix(" -->")).jsonObject
        require(metadata.string("format") == "1") { "Unsupported knowledge document format" }
        return Document(metadata, text.substringAfter('\n', ""))
    }

    fun source(item: CollectedSource): String =
        encode(
            buildJsonObject {
                put("format", "1")
                put("type", "source")
                put("id", item.id)
                put("title", item.title)
                put("receivedAt", item.receivedAt)
                put("source", item.source)
                put("sourceUrl", item.sourceUrl)
                put("rawText", item.rawText)
                put("filesJson", item.filesJson)
                put("state", item.state)
                put("summary", item.summary)
                put("detail", item.detail)
                put("fingerprint", item.fingerprint)
            },
            item.extracted,
        )

    fun source(document: Document): CollectedSource =
        document.metadata.let { m ->
            require(m.string("type") == "source")
            CollectedSource(
                m.string("id"),
                m.string("title"),
                m.number("receivedAt"),
                m.string("source"),
                m.string("sourceUrl"),
                m.string("rawText"),
                m.string("filesJson"),
                m.string("state"),
                document.body,
                m.string("summary"),
                m.string("detail"),
                m.string("fingerprint"),
            )
        }

    fun note(
        note: KnowledgeNote,
        sources: List<KnowledgeNoteSource>,
    ): String =
        encode(
            buildJsonObject {
                put("format", "1")
                put("type", "note")
                put("id", note.id)
                put("title", note.title)
                put("createdAt", note.createdAt)
                put("updatedAt", note.updatedAt)
                put("state", note.state)
                put("kind", note.kind)
                put(
                    "sources",
                    JsonArray(
                        sources.map { source ->
                            buildJsonObject {
                                put("noteId", source.noteId)
                                put("sourceId", source.sourceId)
                                put("title", source.title)
                                put("url", source.url)
                                put("receivedAt", source.receivedAt)
                                put("excerpt", source.excerpt)
                                put("contentHash", source.contentHash)
                                put("completeness", source.completeness)
                            }
                        },
                    ),
                )
            },
            note.body,
        )

    fun note(document: Document): KnowledgeNote =
        document.metadata.let { m ->
            require(m.string("type") == "note")
            KnowledgeNote(
                m.string("id"),
                m.string("title"),
                document.body,
                m.number("createdAt"),
                m.number("updatedAt"),
                m.string("state"),
                kind = m.string("kind"),
            )
        }

    fun sources(document: Document): List<KnowledgeNoteSource> =
        (document.metadata.getValue("sources") as JsonArray).map { element ->
            element.jsonObject.let { m ->
                KnowledgeNoteSource(
                    m.string("noteId"),
                    m.string("sourceId"),
                    m.string("title"),
                    m.string("url"),
                    m.number("receivedAt"),
                    m.string("excerpt"),
                    m.string("contentHash"),
                    m.string("completeness"),
                )
            }
        }

    fun answer(answer: KnowledgeAnswer): String =
        encode(
            buildJsonObject {
                put("format", "1")
                put("type", "answer")
                put("id", answer.id)
                put("question", answer.question)
                put("createdAt", answer.createdAt)
                put("state", answer.state)
                put("sourcesJson", answer.sourcesJson)
                put("targetJson", answer.targetJson)
                put("savedNoteId", answer.savedNoteId)
                put("pendingNoteId", answer.pendingNoteId)
            },
            answer.body,
        )

    fun answer(document: Document): KnowledgeAnswer =
        document.metadata.let { m ->
            require(m.string("type") == "answer")
            KnowledgeAnswer(
                m.string("id"),
                m.string("question"),
                m.number("createdAt"),
                m.string("state"),
                m.string("sourcesJson"),
                m.string("targetJson"),
                document.body,
                m.string("savedNoteId"),
                pendingNoteId = m.string("pendingNoteId"),
            )
        }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.also { require(it.isString) }.content

    private fun JsonObject.number(key: String): Long =
        (getValue(key) as JsonPrimitive).also { require(!it.isString) }.long
}
