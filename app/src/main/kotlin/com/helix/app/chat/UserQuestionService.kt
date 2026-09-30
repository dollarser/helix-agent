package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Questions and dismissals are durable conversation metadata; answers use ordinary input admission. */
class UserQuestionService(
    private val storage: HelixStorage,
) {
    data class Question(
        val id: String,
        val sessionId: String,
        val text: String,
        val options: List<String>,
        val multiple: Boolean,
        val answerRequestId: String = "answer:$id",
    )

    fun answerChanges(sessionId: String): kotlinx.coroutines.flow.Flow<Long> =
        storage.sessionInputs.observeAnswerRevision(sessionId)

    fun offer(
        id: String,
        sessionId: String,
        turnId: String?,
        args: JsonObject,
    ) {
        decode(id, sessionId, args)
        storage.withTransaction {
            if (storage.messages.listBySession(sessionId).none { it.id == id }) {
                storage.messages.append(id, sessionId, turnId, "ASSISTANT", KIND, args.toString())
            }
        }
    }

    suspend fun pending(sessionId: String): List<Question> =
        withContext(Dispatchers.IO) {
            val rows = storage.messages.listBySession(sessionId)
            val dismissed = rows.filter { it.kind == DISMISSED }.mapNotNull(::readBody).toSet()
            rows.filter { it.kind == KIND && it.id !in dismissed && completedQuestion(it) }.mapNotNull { row ->
                val previous = storage.sessionInputs.latestAttempt(sessionId, "answer:${row.id}")
                val requestId =
                    QuestionAnswerAttempt.next(row.id, previous?.state?.name, previous?.sequence)
                        ?: return@mapNotNull null
                try {
                    val body = readBody(row) ?: return@mapNotNull null
                    val args = Json.parseToJsonElement(body) as? JsonObject ?: return@mapNotNull null
                    decode(row.id, sessionId, args).copy(answerRequestId = requestId)
                } catch (_: IllegalArgumentException) {
                    android.util.Log.w("UserQuestion", "Ignored malformed question metadata")
                    null
                }
            }
        }

    private fun readBody(row: com.helix.core.storage.entity.MessageEntity): String? =
        try {
            storage.messages.readContentBounded(row, 32 * 1024)
        } catch (_: java.io.IOException) {
            android.util.Log.w("UserQuestion", "Question metadata is unavailable")
            null
        } catch (_: IllegalArgumentException) {
            android.util.Log.w("UserQuestion", "Question metadata reference is invalid")
            null
        }

    private fun completedQuestion(row: com.helix.core.storage.entity.MessageEntity): Boolean =
        row.turnId?.let { turnId ->
            storage.toolCalls.listByTurn(turnId).any {
                it.name == "ask_user" && it.state == "COMPLETED" &&
                    row.id == "question:${row.sessionId}:$turnId:${it.id}"
            }
        } ?: true

    suspend fun dismiss(question: Question) =
        withContext(Dispatchers.IO) {
            storage.withTransaction {
                val id = "dismiss:${question.id}"
                if (storage.messages.listBySession(question.sessionId).none { it.id == id }) {
                    storage.messages.append(id, question.sessionId, null, "SYSTEM", DISMISSED, question.id)
                }
            }
        }

    suspend fun answer(
        question: Question,
        selected: Set<String>,
        custom: String,
        chat: ChatService,
    ): Boolean {
        require(pending(question.sessionId).any { it == question }) { "Question is no longer pending" }
        require(selected.all { it in question.options })
        require(question.multiple || selected.size <= 1)
        require(custom.length <= 4000)
        val value = custom.trim().ifBlank { question.options.filter { it in selected }.joinToString("; ") }
        require(value.isNotBlank())
        val result =
            chat
                .sendQuestionAnswer(
                    ChatSubmission(question.sessionId, 0, question.answerRequestId, "${question.text}\n$value"),
                ).await()
                .outcome
        return result is ChatSubmissionOutcome.Accepted || result is ChatSubmissionOutcome.Enqueued
    }

    companion object {
        const val KIND = "user_question"
        const val DISMISSED = "user_question_dismissed"

        const val HISTORY = "user_question_history"

        internal fun forkKind(kind: String): String = if (kind == KIND || kind == DISMISSED) HISTORY else kind

        internal fun decode(
            id: String,
            sessionId: String,
            args: JsonObject,
        ): Question {
            fun text(value: kotlinx.serialization.json.JsonElement?): String =
                requireNotNull((value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }).content
            val text = text(args["question"])
            val options =
                args["options"]
                    ?.let { value ->
                        requireNotNull(value as? JsonArray).map { text(it) }
                    }.orEmpty()
            require(text.isNotBlank() && text.length <= 1000)
            require(options.size <= 6 && options.distinct().size == options.size)
            require(options.all { it.isNotBlank() && it.length <= 200 })
            val multiple =
                args["multiple"]?.let {
                    val primitive = it as? kotlinx.serialization.json.JsonPrimitive
                    requireNotNull(primitive?.takeUnless { value -> value.isString }?.booleanOrNull)
                } ?: false
            return Question(id, sessionId, text, options, multiple)
        }
    }
}
