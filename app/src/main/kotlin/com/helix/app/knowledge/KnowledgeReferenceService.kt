package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.repository.KnowledgeReference
import com.helix.core.storage.repository.KnowledgeReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** User-directed snapshots only; never enables Agent library access or exposes private paths. */
class KnowledgeReferenceService(
    private val store: KnowledgeStore,
) {
    data class Candidate(
        val kind: String,
        val id: String,
        val title: String,
        val characters: Int,
        val available: Boolean,
    )

    enum class State { CURRENT, CHANGED, UNAVAILABLE }

    val catalogChanges = combine(store.items, store.notes) { _, _ -> Unit }

    fun states(items: List<KnowledgeReference>) =
        combine(store.items, store.notes) { _, _ ->
            items.associate { "${it.kind}:${it.resourceId}" to state(it) }
        }

    suspend fun candidates(query: String): List<Candidate> =
        withContext(Dispatchers.IO) {
            val notes = if (query.isBlank()) store.notes.first() else store.searchNotes(query)
            val sources = if (query.isBlank()) store.items.first() else store.search(query)
            notes.map { Candidate(it.kind, it.id, it.title, it.body.length, it.body.isNotBlank()) } +
                sources.map { source ->
                    val text = source.extracted.ifBlank { source.rawText }
                    Candidate(
                        "SOURCE",
                        source.id,
                        source.title,
                        text.length,
                        source.state != "DELETING" && text.isNotBlank(),
                    )
                }
        }

    suspend fun select(
        kind: String,
        id: String,
        range: String? = null,
    ): KnowledgeReference =
        withContext(Dispatchers.IO) {
            val document = document(kind, id) ?: error("KNOWLEDGE_REFERENCE_UNAVAILABLE")
            val title = document.title
            val text = document.text
            val actualRange = range ?: if (text.length > 1800) "EXCERPT" else "FULL"
            val selected =
                when (actualRange) {
                    "FULL" -> text
                    "EXCERPT" -> text.take(1800)
                    else -> error("KNOWLEDGE_REFERENCE_RANGE")
                }
            KnowledgeReference(
                kind,
                id,
                title.take(160),
                knowledgeHash(text),
                actualRange,
                selected,
                completeness = document.completeness,
            )
        }

    suspend fun state(reference: KnowledgeReference): State =
        withContext(Dispatchers.IO) {
            val current =
                document(reference.kind, reference.resourceId)
                    ?: return@withContext State.UNAVAILABLE
            if (knowledgeHash(current.text) != reference.sourceHash) return@withContext State.CHANGED
            val selected = if (reference.range == "FULL") current.text else current.text.take(1800)
            if (selected == reference.text) State.CURRENT else State.UNAVAILABLE
        }

    suspend fun valid(items: List<KnowledgeReference>): Boolean {
        KnowledgeReferences.encode(items)
        return items.all { item ->
            when (state(item)) {
                State.CURRENT -> true
                State.CHANGED -> item.keepVersion
                State.UNAVAILABLE -> false
            }
        }
    }

    private data class Document(
        val title: String,
        val text: String,
        val completeness: String,
    )

    private suspend fun document(
        kind: String,
        id: String,
    ): Document? =
        when (kind) {
            "SOURCE" -> {
                store.get(id)?.let { source ->
                    val text = source.extracted.ifBlank { source.rawText }
                    Document(source.title, text, if (source.extracted.isBlank()) "RAW_TEXT" else source.state)
                        .takeIf { source.state != "DELETING" && text.isNotBlank() }
                }
            }

            "NOTE", "KNOWLEDGE" -> {
                store.note(id)?.let { note ->
                    Document(note.title, note.body, "USER_CONTENT")
                        .takeIf { note.kind == kind && note.body.isNotBlank() }
                }
            }

            else -> {
                null
            }
        }
}
