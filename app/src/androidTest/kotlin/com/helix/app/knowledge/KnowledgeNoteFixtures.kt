package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import java.util.UUID

/** Persisted historical fixtures; not a replacement production creation API. */
internal suspend fun createStoredTestNote(
    store: KnowledgeStore,
    ids: List<String>,
    title: String,
): String {
    val id = UUID.randomUUID().toString()
    val sources =
        ids.map { sourceId ->
            val source = requireNotNull(store.get(sourceId))
            val text = source.extracted.ifBlank { source.rawText }
            KnowledgeNoteSource(
                id,
                sourceId,
                source.title,
                source.sourceUrl,
                source.receivedAt,
                text.take(6000),
                knowledgeHash(text),
                source.state,
            )
        }
    store.saveNote(KnowledgeNote(id, title, "", 1, 1), sources)
    return id
}

internal suspend fun KnowledgeNoteService.createEmptyTestNote(title: String): String {
    val id = UUID.randomUUID().toString()
    saveNew(KnowledgeNote(id, title, "", 1, 1), title, "", false)
    return id
}

internal fun encodeAnswerFixtureSources(sources: List<KnowledgeNoteSource>): String =
    org.json
        .JSONArray()
        .apply {
            sources.forEach { source ->
                put(
                    org.json
                        .JSONObject()
                        .put("id", source.sourceId)
                        .put("title", source.title)
                        .put("url", source.url)
                        .put("receivedAt", source.receivedAt)
                        .put("excerpt", source.excerpt)
                        .put("hash", source.contentHash)
                        .put("completeness", source.completeness),
                )
            }
        }.toString()
