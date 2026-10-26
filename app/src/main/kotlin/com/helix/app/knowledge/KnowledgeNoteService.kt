package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** User-created notes and frozen many-to-many evidence; no automatic clustering or source mutation. */
@Suppress("TooManyFunctions") // One mutation/recovery owner for notes and explicit knowledge creation.
class KnowledgeNoteService(
    private val store: KnowledgeStore,
    onStorageFailure: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + knowledgeStorageFailure(onStorageFailure))
    private val mutation = Mutex()
    val notes = store.notes
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

    /** New editor content is not persisted until the user explicitly saves it. */
    suspend fun saveNew(
        note: KnowledgeNote,
        title: String,
        body: String,
        reviewed: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(note.kind in setOf("NOTE", "KNOWLEDGE"))
        require(title.isNotBlank() && title.length <= 160 && body.length <= MAX_KNOWLEDGE_TEXT)
        mutation.withLock {
            check(store.note(note.id) == null)
            store.saveNote(
                note.copy(
                    title = title,
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                    state = if (reviewed) "REVIEWED" else "DRAFT",
                ),
            )
        }
    }

    suspend fun createKnowledge(
        source: KnowledgeNote,
        title: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            require(
                source.body.isNotBlank() && title.isNotBlank() &&
                    title.length <= 160 && body.isNotBlank() && body.length <= MAX_KNOWLEDGE_TEXT,
            )
            mutation.withLock {
                check(store.note(source.id) == source) { "Knowledge source changed" }
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val origin =
                    com.helix.core.storage.repository.KnowledgeReference(
                        source.kind,
                        source.id,
                        source.title,
                        knowledgeHash(source.body),
                        "EXCERPT",
                        source.body.take(1800),
                    )
                val note =
                    KnowledgeNote(
                        id,
                        title,
                        body,
                        now,
                        now,
                        kind = "KNOWLEDGE",
                        originReferencesJson =
                            com.helix.core.storage.repository.KnowledgeReferences
                                .encode(listOf(origin)),
                    )
                store.saveNote(note, store.noteSources(source.id).map { it.copy(noteId = id) })
                id
            }
        }

    suspend fun createFromSources(
        selected: List<com.helix.core.storage.knowledge.CollectedSource>,
        title: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            require(selected.isNotEmpty() && selected.size <= 8 && selected.distinctBy { it.id }.size == selected.size)
            require(title.isNotBlank() && title.length <= 160)
            require(body.isNotBlank() && body.length <= MAX_KNOWLEDGE_TEXT)
            mutation.withLock {
                check(selected.all { it.state != "DELETING" && store.get(it.id) == it })
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val budget = minOf(6000, 24000 / selected.size)
                val links =
                    selected.map { source ->
                        val text = source.extracted.ifBlank { source.rawText }
                        val excerpt = text.take(budget)
                        val hint = if (text.length > budget) "\n[Excerpt truncated to $budget characters]" else ""
                        KnowledgeNoteSource(
                            id,
                            source.id,
                            source.title.take(160),
                            source.sourceUrl.take(2000),
                            source.receivedAt,
                            excerpt + hint,
                            knowledgeHash(text),
                            source.state,
                        )
                    }
                store.saveNote(KnowledgeNote(id, title, body, now, now, kind = "KNOWLEDGE"), links)
                id
            }
        }

    suspend fun saveReply(
        sessionId: String,
        messageId: String,
        title: String,
        text: String,
        references: List<com.helix.core.storage.repository.KnowledgeReference>,
    ): String =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank() && messageId.isNotBlank())
            require(text.isNotBlank() && text.length <= MAX_KNOWLEDGE_TEXT)
            val hash = knowledgeHash(text)
            val id = UUID.nameUUIDFromBytes("reply:$sessionId:$messageId:$hash".toByteArray()).toString()
            mutation.withLock {
                if (store.note(id) == null) {
                    val now = System.currentTimeMillis()
                    store.saveNote(
                        KnowledgeNote(
                            id,
                            title.take(160),
                            text,
                            now,
                            now,
                            originReferencesJson =
                                com.helix.core.storage.repository.KnowledgeReferences
                                    .encode(references),
                            originSessionId = sessionId,
                            originMessageId = messageId,
                            originContentHash = hash,
                        ),
                    )
                }
                id
            }
        }

    suspend fun save(
        expected: KnowledgeNote,
        title: String,
        body: String,
        reviewed: Boolean,
    ) = withContext(Dispatchers.IO) {
        require(title.isNotBlank() && title.length <= 160 && body.length <= MAX_KNOWLEDGE_TEXT)
        mutation.withLock {
            val current = requireNotNull(store.note(expected.id))
            check(current.documentHash == expected.documentHash) { "Document changed; reload before saving" }
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
}
