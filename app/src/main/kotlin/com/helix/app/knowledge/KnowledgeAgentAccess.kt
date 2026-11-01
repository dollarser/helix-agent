package com.helix.app.knowledge

import android.content.SharedPreferences
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Separate, default-off user authority. Tool output is untrusted reference material, never instructions. */
internal class KnowledgeAgentAccess(
    private val prefs: SharedPreferences,
    private val store: KnowledgeStore,
    private val semantic: KnowledgeSemanticService? = null,
    private val readPdf: suspend (
        com.helix.tools.framework.ExecutableToolCall,
        String,
        Int,
        Int,
        Int,
        Int,
    ) -> JsonObject? =
        { _, _, _, _, _, _ -> null },
) : KnowledgeReadTools.Library {
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("agent-read", false))
    val enabled = mutableEnabled.asStateFlow()

    @android.annotation.SuppressLint("UseKtx") // KTX returns Unit; authority changes require a checked durable commit.
    fun setEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("agent-read", value).commit())
        mutableEnabled.value = value
    }

    override fun allowed() = prefs.getBoolean("agent-read", false)

    override suspend fun related(
        query: String,
        references: List<com.helix.core.storage.repository.KnowledgeReference>?,
    ): List<JsonObject> =
        semantic?.search(query, references).orEmpty().map { hit ->
            val kind = hit.document.key.substringBefore(':')
            val id = hit.document.key.substringAfter(':')
            val textHash = knowledgeHash(hit.document.text)
            buildJsonObject {
                put("kind", JsonPrimitive(kind))
                put("id", JsonPrimitive(id))
                put("title", JsonPrimitive(hit.document.title.take(160)))
                put("contentHash", JsonPrimitive(textHash))
                put("sourceRevision", JsonPrimitive(hit.document.revision))
                put("readOffset", JsonPrimitive(hit.start))
                put("endOffset", JsonPrimitive(hit.end))
                put(
                    "excerpt",
                    JsonPrimitive(SemanticIndex.text(hit.document, hit.start, minOf(hit.end, hit.start + 512))),
                )
                put("retrieval", JsonPrimitive("hybrid_candidate_not_full_document"))
                put(
                    "citation",
                    JsonPrimitive(
                        "helix://knowledge/$kind/$id?revision=$textHash&range=${hit.start}:${hit.end}",
                    ),
                )
            }
        }

    override suspend fun originalPdf(
        call: com.helix.tools.framework.ExecutableToolCall,
        id: String,
        file: Int,
        page: Int,
        offset: Int,
        limit: Int,
    ) = readPdf(call, id, file, page, offset, limit)

    override suspend fun search(query: String): JsonObject =
        withContext(Dispatchers.IO) {
            val terms = KnowledgeRetrieval.terms(query)
            val sources =
                if (terms.isEmpty()) {
                    emptyList()
                } else {
                    KnowledgeRetrieval.rank(store.retrievalCandidates(terms), terms).filter { it.state != "DELETING" }
                }
            val notes = store.boundedNoteSearch(query, 8)
            val sourceItems =
                sources
                    .filter {
                        com.helix.app.application.security.ForbiddenContentGuard
                            .reasonFor(it.extracted.ifBlank { it.rawText }) == null
                    }.take(8)
                    .map {
                        val excerpt = it.extracted.ifBlank { it.rawText }.take(512)
                        entry("source", it.id, it.title, excerpt, it.state)
                    }
            val noteItems =
                notes
                    .filter {
                        com.helix.app.application.security.ForbiddenContentGuard
                            .reasonFor(it.body) == null
                    }.map { entry("note", it.id, it.title, it.body.take(512), it.state) }
            buildJsonObject {
                put("untrustedReference", JsonPrimitive(true))
                put("items", JsonArray(sourceItems + noteItems))
            }
        }

    override suspend fun read(
        kind: String,
        id: String,
    ): JsonObject? =
        withContext(Dispatchers.IO) {
            if (kind == "source") {
                store.get(id)?.takeIf { it.state != "DELETING" }?.let {
                    val text = it.extracted.ifBlank { it.rawText }
                    content(
                        JsonObject(
                            entry(kind, id, it.title, "", it.state) +
                                ("category" to JsonPrimitive("SOURCE")) +
                                ("sourceRevision" to JsonPrimitive(knowledgeSourceRevision(it))) +
                                ("originals" to knowledgeOriginalMetadata(it)) +
                                (
                                    "extraction" to (
                                        it.extraction.takeIf(String::isNotBlank)?.let(
                                            kotlinx.serialization.json.Json::parseToJsonElement,
                                        ) ?: JsonArray(emptyList())
                                    )
                                ) +
                                ("completeness" to JsonPrimitive(if (it.extracted.isBlank()) "RAW_TEXT" else it.state)),
                        ),
                        text,
                        it.sourceUrl,
                    )
                }
            } else {
                store.note(id)?.let { note ->
                    val result =
                        content(
                            JsonObject(
                                entry(kind, id, note.title, "", note.state) +
                                    ("category" to JsonPrimitive(note.kind)) +
                                    ("completeness" to JsonPrimitive("USER_CONTENT")),
                            ),
                            note.body,
                            "",
                        )
                    val references =
                        store.noteSources(id).take(8).map { reference ->
                            buildJsonObject {
                                put("id", JsonPrimitive(reference.sourceId))
                                put("title", JsonPrimitive(reference.title.take(160)))
                                put("url", JsonPrimitive(reference.url.take(2000)))
                                put("excerpt", JsonPrimitive(reference.excerpt.take(512)))
                                put("contentHash", JsonPrimitive(reference.contentHash))
                                put("completeness", JsonPrimitive(reference.completeness.take(256)))
                            }
                        }
                    JsonObject(result + ("references" to JsonArray(references)))
                }
            }
        }

    private fun entry(
        kind: String,
        id: String,
        title: String,
        excerpt: String,
        state: String,
    ) = buildJsonObject {
        put("kind", JsonPrimitive(kind))
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title.take(160)))
        put("excerpt", JsonPrimitive(excerpt))
        put("state", JsonPrimitive(state))
    }

    private fun content(
        metadata: JsonObject,
        text: String,
        url: String,
    ) = buildJsonObject {
        metadata.forEach { (key, value) -> put(key, value) }
        put("untrustedReference", JsonPrimitive(true))
        put("text", JsonPrimitive(text))
        put("contentHash", JsonPrimitive(knowledgeHash(text)))
        put("truncated", JsonPrimitive(false))
        put("sourceUrl", JsonPrimitive(url.take(2000)))
    }
}
