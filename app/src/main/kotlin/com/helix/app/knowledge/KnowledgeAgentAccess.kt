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
) : KnowledgeReadTools.Backend {
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("agent-read", false))
    val enabled = mutableEnabled.asStateFlow()

    @android.annotation.SuppressLint("UseKtx") // KTX returns Unit; authority changes require a checked durable commit.
    fun setEnabled(value: Boolean) {
        check(prefs.edit().putBoolean("agent-read", value).commit())
        mutableEnabled.value = value
    }

    override fun allowed() = prefs.getBoolean("agent-read", false)

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
                sources.take(8).map {
                    val excerpt = it.extracted.ifBlank { it.rawText }.take(512)
                    entry("source", it.id, it.title, excerpt, it.state)
                }
            val noteItems = notes.map { entry("note", it.id, it.title, it.body.take(512), it.state) }
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
                    content(entry(kind, id, it.title, "", it.state), text, it.sourceUrl)
                }
            } else {
                store.note(id)?.let { note ->
                    val result = content(entry(kind, id, note.title, "", note.state), note.body, "")
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
        put("text", JsonPrimitive(text.take(12_000)))
        put("contentHash", JsonPrimitive(knowledgeHash(text)))
        put("truncated", JsonPrimitive(text.length > 12_000))
        put("sourceUrl", JsonPrimitive(url.take(2000)))
    }
}
