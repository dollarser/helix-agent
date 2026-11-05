package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.repository.KnowledgeReference
import com.helix.core.storage.repository.KnowledgeReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
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
            // Read authoritative documents even for an empty query: initial cache flows
            // may still be empty before the first library refresh.
            if (query.isBlank()) store.refresh()
            val notes = if (query.isBlank()) store.notes.value else store.searchNotes(query)
            val sources = if (query.isBlank()) store.items.value else store.search(query)
            notes.map {
                Candidate(
                    it.kind,
                    it.id,
                    it.title,
                    it.body.codePointCount(0, it.body.length),
                    it.body.isNotBlank(),
                )
            } +
                sources.map { source ->
                    val text = source.extracted.ifBlank { source.rawText }
                    Candidate(
                        "SOURCE",
                        source.id,
                        source.title,
                        text.codePointCount(0, text.length),
                        source.state != "DELETING" && text.isNotBlank(),
                    )
                }
        }

    suspend fun select(
        kind: String,
        id: String,
    ): KnowledgeReference =
        withContext(Dispatchers.IO) {
            val document = document(kind, id) ?: error("KNOWLEDGE_REFERENCE_UNAVAILABLE")
            KnowledgeReference(
                kind,
                id,
                document.title.take(160),
                document.revision,
                document.text.codePointCount(0, document.text.length),
                document.completeness,
            )
        }

    suspend fun state(reference: KnowledgeReference): State =
        withContext(Dispatchers.IO) {
            val current = document(reference.kind, reference.resourceId) ?: return@withContext State.UNAVAILABLE
            if (current.revision == reference.sourceHash && current.completeness == reference.completeness) {
                State.CURRENT
            } else {
                State.CHANGED
            }
        }

    suspend fun valid(items: List<KnowledgeReference>): Boolean {
        KnowledgeReferences.encode(items)
        return items.all { state(it) == State.CURRENT }
    }

    /** Local preview/copy is distinct from model input; a changed source is never substituted. */
    suspend fun preview(reference: KnowledgeReference): String? =
        withContext(Dispatchers.IO) {
            document(reference.kind, reference.resourceId)
                ?.takeIf {
                    it.revision == reference.sourceHash && it.completeness == reference.completeness
                }?.text
        }

    /** Explicit save-as-note keeps a bounded provenance excerpt independently of lazy chat references. */
    suspend fun freeze(reference: KnowledgeReference): com.helix.core.storage.repository.KnowledgeOrigin {
        val text = preview(reference)
        return com.helix.core.storage.repository.KnowledgeOrigin(
            reference.kind,
            reference.resourceId,
            reference.title,
            reference.sourceHash,
            if (text == null) "METADATA" else "EXCERPT",
            text?.take(1800).orEmpty(),
            completeness = reference.completeness,
        )
    }

    private data class Document(
        val title: String,
        val text: String,
        val completeness: String,
        val revision: String = knowledgeHash(text),
    )

    private suspend fun document(
        kind: String,
        id: String,
    ): Document? =
        when (kind) {
            "SOURCE" -> {
                store.get(id)?.let { source ->
                    val text = source.extracted.ifBlank { source.rawText }
                    Document(
                        source.title,
                        text,
                        if (source.extracted.isBlank()) "RAW_TEXT" else source.state,
                        knowledgeSourceRevision(source),
                    ).takeIf { source.state != "DELETING" && text.isNotBlank() }
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
