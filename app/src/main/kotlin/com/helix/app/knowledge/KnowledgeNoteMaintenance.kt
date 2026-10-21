package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares the generation mutation boundary; stale editors cannot delete or export a different revision. */
class KnowledgeNoteMaintenance internal constructor(
    private val store: KnowledgeStore,
    private val mutation: Mutex,
    private val recovery: Job,
) {
    val sourceChanges = store.items

    suspend fun changed(sources: List<KnowledgeNoteSource>): Boolean =
        withContext(Dispatchers.IO) {
            sources.any { frozen ->
                val current = store.get(frozen.sourceId)
                current == null || current.state == "DELETING" || current.title.take(160) != frozen.title ||
                    current.sourceUrl.take(2000) != frozen.url ||
                    knowledgeHash(current.extracted.ifBlank { current.rawText }) != frozen.contentHash
            }
        }

    suspend fun refresh(
        expected: KnowledgeNote,
        frozen: List<KnowledgeNoteSource>,
    ) = withContext(Dispatchers.IO) {
        recovery.join()
        mutation.withLock {
            check(store.note(expected.id) == expected && expected.generationState != "RUNNING")
            check(store.noteSources(expected.id) == frozen)
            val budget = minOf(6000, 24_000 / frozen.size.coerceAtLeast(1))
            val currentSources = frozen.map { requireNotNull(store.get(it.sourceId)) }
            val replacements =
                frozen.map { previous ->
                    val current = currentSources.single { it.id == previous.sourceId }
                    check(current.state !in setOf("IMPORTING", "QUEUED", "EXTRACTING", "DELETING"))
                    val text = current.extracted.ifBlank { current.rawText }
                    previous.copy(
                        title = current.title.take(160),
                        url = current.sourceUrl.take(2000),
                        receivedAt = current.receivedAt,
                        excerpt = text.take(budget) + if (text.length > budget) "\n[Excerpt truncated]" else "",
                        contentHash = knowledgeHash(text),
                        completeness = "${current.state}:${current.detail}",
                    )
                }
            store.refreshNote(
                expected,
                frozen,
                currentSources,
                expected.copy(
                    updatedAt = maxOf(System.currentTimeMillis(), expected.updatedAt + 1),
                    state = "DRAFT",
                    generatedDraft = "",
                    generationState = "IDLE",
                ),
                replacements,
            )
        }
    }

    suspend fun remove(expected: KnowledgeNote) =
        withContext(Dispatchers.IO) {
            recovery.join()
            mutation.withLock { store.removeNote(expected) }
        }

    suspend fun export(expected: KnowledgeNote): String =
        withContext(Dispatchers.IO) {
            recovery.join()
            mutation.withLock {
                check(store.note(expected.id) == expected && expected.generationState != "RUNNING")
                knowledgeMarkdown(expected, store.noteSources(expected.id))
            }
        }
}
