package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** User-created notes and frozen many-to-many evidence; no automatic clustering or source mutation. */
class KnowledgeNoteService(
    private val store: KnowledgeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutation = Mutex()
    val notes = store.notes
    val failure = MutableStateFlow<String?>(null)
    val recovery =
        scope.launch {
            store.interruptedNotes().forEach { note ->
                store.saveNote(note.copy(generationState = "INTERRUPTED"))
            }
        }

    fun close() = scope.cancel()

    suspend fun sources(id: String) = withContext(Dispatchers.IO) { store.noteSources(id) }

    suspend fun get(id: String) = withContext(Dispatchers.IO) { store.note(id) }

    suspend fun search(query: String) = withContext(Dispatchers.IO) { store.searchNotes(query) }

    val management = KnowledgeNoteMaintenance(store, mutation, recovery)

    suspend fun create(
        sourceIds: List<String>,
        title: String,
    ): String =
        withContext(Dispatchers.IO) {
            require(sourceIds.size <= 8 && sourceIds.distinct().size == sourceIds.size)
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val budget = if (sourceIds.isEmpty()) 6000 else minOf(6000, 24_000 / sourceIds.size)
            val sources =
                sourceIds.map { sourceId ->
                    val source = requireNotNull(store.get(sourceId))
                    require(source.state != "DELETING")
                    val text = source.extracted.ifBlank { source.rawText }
                    val truncation = if (text.length > budget) "\n[Excerpt truncated to $budget characters]" else ""
                    val excerpt = text.take(budget) + truncation
                    KnowledgeNoteSource(
                        id,
                        sourceId,
                        source.title.take(160),
                        source.sourceUrl.take(2000),
                        source.receivedAt,
                        excerpt,
                        knowledgeHash(text),
                        "${source.state}:${source.detail}",
                    )
                }
            store.saveNote(KnowledgeNote(id, title.take(160), "", now, now), sources)
            id
        }

    suspend fun save(
        id: String,
        title: String,
        body: String,
        reviewed: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(title.isNotBlank() && title.length <= 160 && body.length <= MAX_KNOWLEDGE_TEXT)
        mutation.withLock {
            val current = requireNotNull(store.note(id))
            check(current.generationState != "RUNNING")
            store.saveNote(
                current.copy(
                    title = title,
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                    state = if (reviewed) "REVIEWED" else "DRAFT",
                    generatedDraft = if (reviewed) "" else current.generatedDraft,
                    generationState = if (reviewed) "IDLE" else current.generationState,
                ),
            )
        }
    }

    suspend fun prepare(
        id: String,
        analysis: KnowledgeAnalysis,
        providerId: String,
        model: String,
    ) = withContext(Dispatchers.IO) {
        analysis.prepare(requireNotNull(store.note(id)), store.noteSources(id), providerId, model)
    }

    // Model failures retain the user's draft and leave a durable failure state.
    @Suppress("TooGenericExceptionCaught")
    fun generate(request: PreparedKnowledgeAnalysis) =
        scope.launch {
            recovery.join()
            failure.value = null
            val attempt = UUID.randomUUID().toString()
            val target =
                org.json
                    .JSONObject()
                    .put("providerId", request.destination.providerId)
                    .put("model", request.model)
                    .put("origin", request.destination.origin)
                    .put("residence", request.destination.residence.name)
                    .put("inputHash", request.inputHash)
                    .put("inputCharacters", request.inputCharacters)
                    .put("attempt", attempt)
                    .toString()
            var admitted = false
            try {
                mutation.withLock {
                    val note = requireNotNull(store.note(request.note.id))
                    check(note == request.note && store.noteSources(note.id) == request.sources)
                    check(note.generationState != "RUNNING")
                    store.saveNote(note.copy(generationState = "RUNNING", generationTarget = target))
                    admitted = true
                }
                val generated = request.execute() + sourceFooter(request.sources)
                finish(request.note.id, target, "READY", generated)
            } catch (cancel: CancellationException) {
                if (admitted) withContext(NonCancellable) { finish(request.note.id, target, "INTERRUPTED", "") }
                throw cancel
            } catch (_: Exception) {
                failure.value = request.note.id
                if (admitted) finish(request.note.id, target, "FAILED", "")
            }
        }

    private suspend fun finish(
        id: String,
        target: String,
        state: String,
        generated: String,
    ) {
        mutation.withLock {
            val note = requireNotNull(store.note(id))
            if (note.generationState == "RUNNING" && note.generationTarget == target) {
                store.saveNote(
                    note.copy(
                        generationState = state,
                        generatedDraft = generated,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun sourceFooter(sources: List<KnowledgeNoteSource>): String =
        "\n\n---\nSources (frozen selected excerpts):\n" +
            sources.joinToString("\n") {
                "- [source:${it.sourceId}] ${it.title} ${it.url} · ${it.completeness}"
            }
}
