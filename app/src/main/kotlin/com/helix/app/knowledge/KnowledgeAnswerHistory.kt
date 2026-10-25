package com.helix.app.knowledge

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
import org.json.JSONArray

/** Retained answer records only. All new model requests belong to the conversation runtime. */
class KnowledgeAnswerHistory(
    private val store: KnowledgeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutation = Mutex()
    val answers = store.answers
    val recovery = scope.launch { store.recoverAnswers() }

    fun close() = scope.cancel()

    suspend fun save(id: String): String =
        withContext(Dispatchers.IO) {
            recovery.join()
            mutation.withLock {
                val answer = requireNotNull(store.answer(id))
                store.saveAnswerAsNote(id, decodeSources(answer.sourcesJson))
            }
        }

    companion object {
        internal fun decodeSources(value: String): List<KnowledgeNoteSource> {
            val array = JSONArray(value)
            require(array.length() <= 8)
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
