package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeAnswer
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Explicit retrieval and one confirmed, immutable model request; never uploads the whole library. */
class KnowledgeQuestionService(
    private val store: KnowledgeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutation = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    val answers = store.answers
    val failure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val recovery = scope.launch { store.recoverAnswers() }

    fun close() = scope.cancel()

    suspend fun search(question: String): List<CollectedSource> =
        withContext(Dispatchers.IO) {
            val terms = KnowledgeRetrieval.terms(question)
            if (terms.isEmpty()) {
                emptyList()
            } else {
                KnowledgeRetrieval.rank(
                    store.retrievalCandidates(terms).filter {
                        it.state !=
                            "DELETING"
                    },
                    terms,
                )
            }
        }

    suspend fun evidence(
        question: String,
        ids: List<String>,
    ): List<KnowledgeNoteSource> =
        withContext(Dispatchers.IO) {
            require(question.isNotBlank() && question.length <= 2000)
            require(ids.isNotEmpty() && ids.size <= 8 && ids.distinct().size == ids.size)
            ids
                .map { KnowledgeRetrieval.evidence(requireNotNull(store.get(it)), question, 24_000 / ids.size) }
                .also { require(it.all { source -> source.excerpt.isNotBlank() }) }
        }

    suspend fun prepare(
        question: String,
        sources: List<KnowledgeNoteSource>,
        analysis: KnowledgeAnalysis,
        providerId: String,
        model: String,
    ): PreparedKnowledgeAnalysis =
        withContext(Dispatchers.IO) {
            require(question.isNotBlank() && question.length <= 2000)
            validate(sources)
            val now = System.currentTimeMillis()
            analysis.prepare(
                KnowledgeNote(UUID.randomUUID().toString(), question.take(160), question, now, now),
                sources,
                providerId,
                model,
                true,
            )
        }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    /** Model failure is a durable outcome, not a successful empty answer or an automatic retry. */
    @Suppress("TooGenericExceptionCaught")
    fun ask(request: PreparedKnowledgeAnalysis): Job {
        val id = request.note.id
        val job =
            scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                recovery.join()
                failure.value = null
                val target =
                    JSONObject()
                        .put("providerId", request.destination.providerId)
                        .put("origin", request.destination.origin)
                        .put("model", request.model)
                        .put("inputHash", request.inputHash)
                        .put("inputCharacters", request.inputCharacters)
                        .toString()
                val answer =
                    KnowledgeAnswer(
                        id,
                        request.note.body,
                        System.currentTimeMillis(),
                        "RUNNING",
                        encodeSources(request.sources),
                        target,
                    )
                var admitted = false
                try {
                    mutation.withLock {
                        if (store.answer(id) != null) return@launch
                        validate(request.sources)
                        store.saveAnswer(answer)
                        admitted = true
                    }
                    val body = request.execute()
                    // Validate again at the persistence boundary, including injected/test executors.
                    val cited = Regex("\\[source:([^]]+)]").findAll(body).map { it.groupValues[1] }.toSet()
                    check(body.isNotBlank() && body.length <= 24_000 && cited.isNotEmpty())
                    check(request.sources.map { it.sourceId }.containsAll(cited))
                    store.saveAnswer(answer.copy(state = "READY", body = body))
                } catch (cancel: CancellationException) {
                    if (admitted) withContext(NonCancellable) { store.saveAnswer(answer.copy(state = "INTERRUPTED")) }
                    throw cancel
                } catch (_: Exception) {
                    failure.value = id
                    if (admitted) store.saveAnswer(answer.copy(state = "FAILED"))
                }
            }
        val existing = jobs.putIfAbsent(id, job)
        if (existing != null) {
            job.cancel()
            return existing
        }
        job.invokeOnCompletion { jobs.remove(id, job) }
        job.start()
        return job
    }

    suspend fun save(id: String): String =
        withContext(Dispatchers.IO) {
            mutation.withLock {
                val answer = requireNotNull(store.answer(id))
                store.saveAnswerAsNote(id, decodeSources(answer.sourcesJson))
            }
        }

    private suspend fun validate(sources: List<KnowledgeNoteSource>) {
        require(
            sources.isNotEmpty() && sources.size <= 8 && sources.map { it.sourceId }.distinct().size == sources.size,
        )
        require(sources.sumOf { it.excerpt.length } <= 26_000)
        sources.forEach { source ->
            val current = requireNotNull(store.get(source.sourceId))
            check(source.contentHash == knowledgeHash(current.extracted.ifBlank { current.rawText }))
            check(source.title == current.title.take(160) && source.url == current.sourceUrl.take(2000))
            check(
                source.receivedAt == current.receivedAt &&
                    source.completeness.startsWith("${current.state}:${current.detail};"),
            )
        }
    }

    companion object {
        internal fun encodeSources(sources: List<KnowledgeNoteSource>): String =
            JSONArray()
                .apply {
                    sources.forEach { s ->
                        put(
                            JSONObject()
                                .put("id", s.sourceId)
                                .put("title", s.title)
                                .put("url", s.url)
                                .put(
                                    "receivedAt",
                                    s.receivedAt,
                                ).put("excerpt", s.excerpt)
                                .put("hash", s.contentHash)
                                .put("completeness", s.completeness),
                        )
                    }
                }.toString()

        internal fun decodeSources(value: String): List<KnowledgeNoteSource> {
            val array = JSONArray(value)
            return (0 until array.length()).map { i ->
                val s = array.getJSONObject(i)
                KnowledgeNoteSource(
                    "",
                    s.getString("id"),
                    s.getString("title"),
                    s.getString("url"),
                    s.getLong("receivedAt"),
                    s.getString("excerpt"),
                    s.getString("hash"),
                    s.getString("completeness"),
                )
            }
        }
    }
}
