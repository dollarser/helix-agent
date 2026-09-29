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
    )

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
            val dismissed = rows.filter { it.kind == DISMISSED }.mapNotNull { storage.messages.readContent(it) }.toSet()
            rows
                .filter {
                    it.kind == KIND && it.id !in dismissed && storage.sessionInputs.get(answerId(it.id)) == null &&
                        completedQuestion(it)
                }.map {
                    decode(
                        it.id,
                        sessionId,
                        Json.parseToJsonElement(requireNotNull(storage.messages.readContent(it))) as JsonObject,
                    )
                }
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
                    ChatSubmission(question.sessionId, 0, answerId(question.id), "${question.text}\n$value"),
                ).await()
                .outcome
        return result is ChatSubmissionOutcome.Accepted || result is ChatSubmissionOutcome.Enqueued
    }

    companion object {
        const val KIND = "user_question"
        const val DISMISSED = "user_question_dismissed"

        private fun answerId(id: String) = "answer:$id"

        internal fun decode(
            id: String,
            sessionId: String,
            args: JsonObject,
        ): Question {
            val text = requireNotNull(args["question"]?.jsonPrimitive?.content)
            val options = (args["options"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            require(text.isNotBlank() && text.length <= 1000)
            require(options.size <= 6 && options.distinct().size == options.size)
            require(options.all { it.isNotBlank() && it.length <= 200 })
            return Question(id, sessionId, text, options, args["multiple"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
    }
}
