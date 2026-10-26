package com.helix.core.storage.knowledge

import android.content.Context
import com.helix.core.storage.content.DocumentDraftRecovery
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.content.MediaStoreDocuments
import com.helix.core.storage.content.PrivateDocumentStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer

data class CollectedSource(
    val id: String,
    val title: String,
    val receivedAt: Long,
    val source: String,
    val sourceUrl: String,
    val rawText: String,
    val filesJson: String = "[]",
    val state: String = "IMPORTING",
    val extracted: String = "",
    val summary: String = "",
    val detail: String = "",
    val fingerprint: String = "",
    val documentHash: String = "",
)

/** Open documents are authoritative; observed flows/search can always be rebuilt. */
@Suppress("TooManyFunctions")
class KnowledgeStore(
    val documents: DocumentStorage,
    private val history: DocumentStorage,
) {
    constructor(context: Context, name: String = "library") : this(
        MediaStoreDocuments(context, name),
        PrivateDocumentStorage(java.io.File(context.filesDir, "agent-library-history/$name").toPath()),
    )

    private val mutation = Mutex()
    private val sourceState = MutableStateFlow(emptyList<CollectedSource>())
    private val noteState = MutableStateFlow(emptyList<KnowledgeNote>())
    private val answerState = MutableStateFlow(emptyList<KnowledgeAnswer>())
    val items = sourceState.asStateFlow()
    val notes = noteState.asStateFlow()
    val answers = answerState.asStateFlow()

    suspend fun restoreLibrary(source: com.helix.core.storage.content.DocumentReadAccess): Int =
        mutation.withLock {
            val count = KnowledgeLibraryRestore(documents).restore(source)
            sources()
            noteRecords()
            count
        }

    data class PendingSave(
        val token: String,
        val title: String,
        val body: String,
        val hash: String,
    )

    private val pendingState = MutableStateFlow(emptyList<PendingSave>())
    val pendingSaves = pendingState.asStateFlow()

    private data class Record(
        val file: DocumentStorage.File,
        val document: KnowledgeDocumentCodec.Document,
    )

    private fun storage(kind: String) = if (kind in setOf("answers", "origins")) history else documents

    private fun records(kind: String): List<Record> =
        storage(kind)
            .list(kind)
            .map { file ->
                val text =
                    Charsets.UTF_8
                        .newDecoder()
                        .decode(ByteBuffer.wrap(file.bytes))
                        .toString()
                Record(file, KnowledgeDocumentCodec.decode(text))
            }.also { entries ->
                val ids =
                    entries.map {
                        it.document.metadata
                            .getValue("id")
                            .jsonPrimitive.content
                    }
                check(ids.distinct().size == ids.size) { "DOCUMENT_DUPLICATE_IDENTITY" }
            }

    private fun sources(): List<CollectedSource> =
        records("sources")
            .map { KnowledgeDocumentCodec.source(it.document).copy(documentHash = it.file.hash) }
            .sortedByDescending { it.receivedAt }
            .also { sourceState.value = it }

    private fun noteRecords(): List<Pair<Record, KnowledgeNote>> =
        records("notes")
            .map {
                val note = KnowledgeDocumentCodec.note(it.document).copy(documentHash = it.file.hash)
                val origin = find("origins", note.id)?.document?.metadata
                it to
                    note.copy(
                        generatedDraft =
                            origin
                                ?.getValue("draft")
                                ?.jsonPrimitive
                                ?.content
                                .orEmpty(),
                        generationState = origin?.getValue("generationState")?.jsonPrimitive?.content ?: "IDLE",
                        generationTarget =
                            origin
                                ?.getValue("generationTarget")
                                ?.jsonPrimitive
                                ?.content
                                .orEmpty(),
                        originReferencesJson = origin?.get("references")?.jsonPrimitive?.content ?: "[]",
                        originSessionId =
                            origin
                                ?.get("session")
                                ?.jsonPrimitive
                                ?.content
                                .orEmpty(),
                        originMessageId =
                            origin
                                ?.get("message")
                                ?.jsonPrimitive
                                ?.content
                                .orEmpty(),
                        originContentHash =
                            origin
                                ?.get("contentHash")
                                ?.jsonPrimitive
                                ?.content
                                .orEmpty(),
                    )
            }.sortedByDescending { it.second.updatedAt }
            .also { entries -> noteState.value = entries.map { it.second } }

    private fun answerRecords(): List<Pair<Record, KnowledgeAnswer>> =
        records("answers")
            .map { it to KnowledgeDocumentCodec.answer(it.document).copy(documentHash = it.file.hash) }
            .sortedByDescending { it.second.createdAt }
            .also { entries ->
                answerState.value = entries.map { it.second }
            }

    suspend fun refresh() =
        mutation.withLock {
            sources()
            noteRecords()
            answerRecords()
            pendingDocuments()
            Unit
        }

    private fun pendingDocuments(): List<PendingSave> {
        val recovery = documents as? DocumentDraftRecovery ?: return emptyList()
        return recovery
            .drafts()
            .map { draft ->
                val text =
                    Charsets.UTF_8
                        .newDecoder()
                        .decode(ByteBuffer.wrap(draft.file.bytes))
                        .toString()
                val document = KnowledgeDocumentCodec.decode(text)
                val title =
                    document.metadata
                        .getValue("title")
                        .jsonPrimitive.content
                PendingSave(draft.token, title, document.body, draft.file.hash)
            }.also { pendingState.value = it }
    }

    suspend fun recoverSaveAsNote(token: String): String =
        mutation.withLock {
            val pending = pendingDocuments().single { it.token == token }
            val id =
                java.util.UUID
                    .nameUUIDFromBytes("recovered-document:$token".toByteArray())
                    .toString()
            val current = noteRecords().singleOrNull { it.second.id == id }?.second
            if (current == null) {
                val now = System.currentTimeMillis()
                saveNoteDocument(KnowledgeNote(id, pending.title, pending.body, now, now))
            } else {
                check(current.body == pending.body) { "Recovered note changed; retained" }
            }
            requireNotNull(documents as? DocumentDraftRecovery).completeDraft(token, pending.hash)
            pendingDocuments()
            id
        }

    suspend fun get(id: String) = mutation.withLock { sources().singleOrNull { it.id == id } }

    suspend fun duplicate(fingerprint: String) =
        mutation.withLock {
            sources().firstOrNull { fingerprint.isNotEmpty() && it.fingerprint == fingerprint }
        }

    suspend fun unfinished() =
        mutation.withLock {
            sources().filter { it.state in setOf("IMPORTING", "EXTRACTING", "QUEUED", "DELETING") }
        }

    suspend fun insert(item: CollectedSource) =
        mutation.withLock {
            check(sources().none { it.id == item.id })
            put("sources", item.id, KnowledgeDocumentCodec.source(item), "")
            sources().single { it.id == item.id }
        }

    suspend fun save(item: CollectedSource) =
        mutation.withLock {
            put("sources", item.id, KnowledgeDocumentCodec.source(item), item.documentHash)
            sources().single { it.id == item.id }
        }

    suspend fun remove(id: String) =
        mutation.withLock {
            find("sources", id)?.let { documents.delete(it.file) }
            sources()
            Unit
        }

    suspend fun note(id: String) = mutation.withLock { noteRecords().singleOrNull { it.second.id == id }?.second }

    suspend fun noteSources(id: String) =
        mutation.withLock {
            noteRecords()
                .singleOrNull { it.second.id == id }
                ?.first
                ?.let {
                    KnowledgeDocumentCodec.sources(
                        it.document,
                    )
                }.orEmpty()
        }

    suspend fun interruptedNotes() =
        mutation.withLock {
            noteRecords().map { it.second }.filter { it.generationState == "RUNNING" }
        }

    suspend fun saveNote(
        note: KnowledgeNote,
        sources: List<KnowledgeNoteSource>? = null,
    ) = mutation.withLock {
        saveNoteDocument(note, sources)
    }

    private fun saveNoteDocument(
        note: KnowledgeNote,
        sources: List<KnowledgeNoteSource>? = null,
    ) {
        val current = find("notes", note.id)
        check(current?.file?.hash.orEmpty() == note.documentHash) { "DOCUMENT_CONFLICT_RELOAD" }
        val links = sources ?: current?.let { KnowledgeDocumentCodec.sources(it.document) }.orEmpty()
        check(links.all { it.noteId == note.id } && links.distinctBy { it.sourceId }.size == links.size)
        saveOrigin(note)
        val markdown = KnowledgeDocumentCodec.note(note, links)
        if (current == null || !current.file.bytes.contentEquals(markdown.toByteArray())) {
            put("notes", note.id, markdown, note.documentHash)
        }
        noteRecords()
    }

    suspend fun refreshNote(
        expected: KnowledgeNote,
        frozen: List<KnowledgeNoteSource>,
        currentSources: List<CollectedSource>,
        updated: KnowledgeNote,
        replacements: List<KnowledgeNoteSource>,
    ) = mutation.withLock {
        val current = noteRecords().single { it.second.id == expected.id }
        check(current.second == expected && KnowledgeDocumentCodec.sources(current.first.document) == frozen)
        val observed = sources()
        check(currentSources.all { it in observed }) { "Source changed during reference refresh" }
        saveNoteDocument(updated, replacements)
    }

    suspend fun removeNote(expected: KnowledgeNote) =
        mutation.withLock {
            val current = noteRecords().single { it.second.id == expected.id }
            check(current.second == expected && expected.generationState != "RUNNING") { "Note changed or generating" }
            answerRecords().map { it.second }.filter { it.savedNoteId == expected.id }.forEach {
                put("answers", it.id, KnowledgeDocumentCodec.answer(it.copy(savedNoteId = "")), it.documentHash)
            }
            documents.delete(current.first.file)
            noteRecords()
            answerRecords()
            Unit
        }

    suspend fun boundedNoteSearch(
        query: String,
        limit: Int = 16,
    ): List<KnowledgeNote> {
        require(limit in 1..16)
        return searchNotes(query).take(limit)
    }

    suspend fun searchNotes(query: String) =
        mutation.withLock {
            val terms = terms(query)
            noteRecords().map { it.second }.filter { matches(it.title + "\n" + it.body, terms) }
        }

    suspend fun search(query: String) =
        mutation.withLock {
            val terms = terms(query)
            sources().filter {
                val text =
                    listOf(
                        it.title,
                        it.rawText,
                        it.sourceUrl,
                        it.extracted,
                        it.summary,
                        it.filesJson,
                    ).joinToString("\n")
                matches(text, terms)
            }
        }

    suspend fun retrievalCandidates(terms: List<String>) =
        mutation.withLock {
            require(terms.isNotEmpty() && terms.size <= 24 && terms.all { it.isNotBlank() && it.length <= 200 })
            sources()
                .filter { source ->
                    terms.any { (source.title + source.rawText + source.extracted).contains(it, true) }
                }.take(128)
        }

    suspend fun answer(id: String) = mutation.withLock { answerRecords().singleOrNull { it.second.id == id }?.second }

    suspend fun saveAnswer(answer: KnowledgeAnswer) =
        mutation.withLock {
            put("answers", answer.id, KnowledgeDocumentCodec.answer(answer), answer.documentHash)
            answerRecords()
            Unit
        }

    suspend fun recoverAnswers() =
        mutation.withLock {
            answerRecords().map { it.second }.filter { it.state == "RUNNING" }.forEach { answer ->
                put(
                    "answers",
                    answer.id,
                    KnowledgeDocumentCodec.answer(answer.copy(state = "INTERRUPTED")),
                    answer.documentHash,
                )
            }
            answerRecords()
            Unit
        }

    suspend fun saveAnswerAsNote(
        id: String,
        sources: List<KnowledgeNoteSource>,
    ): String =
        mutation.withLock {
            var answer = answerRecords().single { it.second.id == id }.second
            check(answer.state == "READY")
            if (answer.savedNoteId.isNotEmpty() && noteRecords().any { it.second.id == answer.savedNoteId }) {
                return@withLock answer.savedNoteId
            }
            val noteId =
                answer.pendingNoteId.ifEmpty {
                    java.util.UUID
                        .randomUUID()
                        .toString()
                }
            if (answer.pendingNoteId.isEmpty()) {
                put(
                    "answers",
                    id,
                    KnowledgeDocumentCodec.answer(answer.copy(pendingNoteId = noteId)),
                    answer.documentHash,
                )
                answer = answerRecords().single { it.second.id == id }.second
            }
            if (noteRecords().none { it.second.id == noteId }) {
                val now = System.currentTimeMillis()
                saveNoteDocument(
                    KnowledgeNote(noteId, answer.question.take(160), answer.body, now, now),
                    sources.map { it.copy(noteId = noteId) },
                )
            } else {
                check(noteRecords().single { it.second.id == noteId }.second.body == answer.body) {
                    "Pending saved note changed; content retained"
                }
            }
            put(
                "answers",
                id,
                KnowledgeDocumentCodec.answer(answer.copy(savedNoteId = noteId, pendingNoteId = "")),
                answer.documentHash,
            )
            answerRecords()
            noteId
        }

    private fun find(
        kind: String,
        id: String,
    ): Record? =
        records(kind).singleOrNull {
            it.document.metadata
                .getValue("id")
                .jsonPrimitive.content == id
        }

    private fun put(
        kind: String,
        id: String,
        markdown: String,
        expectedHash: String,
    ) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val previous = find(kind, id)
        check(previous?.file?.hash.orEmpty() == expectedHash) { "DOCUMENT_CONFLICT_RELOAD" }
        storage(kind).save(kind, "$id.md", markdown.toByteArray(), previous?.file)
    }

    private fun saveOrigin(note: KnowledgeNote) {
        val previous = find("origins", note.id)
        val hasOrigin =
            note.originReferencesJson != "[]" || note.originSessionId.isNotEmpty() ||
                note.originMessageId.isNotEmpty() || note.generatedDraft.isNotEmpty() ||
                note.generationState != "IDLE" || note.generationTarget.isNotEmpty()
        if (previous == null && !hasOrigin) return
        val metadata =
            buildJsonObject {
                put("format", JsonPrimitive("1"))
                put("id", JsonPrimitive(note.id))
                put("references", JsonPrimitive(note.originReferencesJson))
                put("session", JsonPrimitive(note.originSessionId))
                put("message", JsonPrimitive(note.originMessageId))
                put("contentHash", JsonPrimitive(note.originContentHash))
                put("draft", JsonPrimitive(note.generatedDraft))
                put("generationState", JsonPrimitive(note.generationState))
                put("generationTarget", JsonPrimitive(note.generationTarget))
            }
        val bytes = KnowledgeDocumentCodec.encode(metadata, "").toByteArray()
        history.save("origins", "${note.id}.md", bytes, previous?.file)
    }

    private fun terms(query: String): List<String> {
        require(query.length in 1..200)
        if (query.any { it == '"' || it == '*' }) return listOf(query.trim()).filter { it.isNotEmpty() }
        return Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
    }

    private fun matches(
        text: String,
        terms: List<String>,
    ) = terms.isNotEmpty() && terms.all { text.contains(it, true) }
}
