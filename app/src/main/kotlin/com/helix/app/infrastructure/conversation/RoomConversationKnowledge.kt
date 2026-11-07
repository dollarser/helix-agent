package com.helix.app.infrastructure.conversation

import android.database.sqlite.SQLiteException
import com.helix.app.knowledge.KnowledgeNoteService
import com.helix.app.knowledge.KnowledgeReferenceService
import com.helix.core.model.TurnState
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationKnowledge
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Captures one consistent reply in Room, then publishes the requested note outside the transaction. */
internal class RoomConversationKnowledge(
    private val storage: HelixStorage,
    private val references: KnowledgeReferenceService?,
    private val notes: KnowledgeNoteService?,
    private val strings: (Int, Array<out Any>) -> String,
) : ConversationKnowledge {
    override suspend fun valid(items: List<KnowledgeReference>): Boolean =
        items.isEmpty() || references?.valid(items) == true

    override suspend fun saveReply(
        sessionId: String,
        messageId: String,
    ): Boolean =
        try {
            val publisher = requireNotNull(notes)
            val reply = captureReply(sessionId, messageId)
            publisher.saveReply(
                sessionId,
                messageId,
                strings(com.helix.app.R.string.knowledge_reply_title, emptyArray()),
                reply.body,
                reply.references,
            )
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: SQLiteException) {
            false
        }

    private fun captureReply(
        sessionId: String,
        messageId: String,
    ): ReplySnapshot {
        var snapshot: ReplySnapshot? = null
        storage.withTransaction {
            val message = storage.messages.resolve(messageId)
            check(message.sessionId == sessionId && message.role == "ASSISTANT" && message.kind == "TEXT")
            check(message.supersededBy == null)
            val turn = storage.turns.resolve(requireNotNull(message.turnId))
            check(turn.sessionId == sessionId && TurnState.valueOf(turn.state).isTerminal)
            val body = requireNotNull(storage.messages.readContentBounded(message, MAX_REPLY_BYTES))
            require(body.isNotBlank())
            val input =
                storage.messages.byTurn(turn.id).lastOrNull {
                    it.role == "USER" && it.sequence < message.sequence
                }
            val references = input?.let { storage.sessionInputs.forMessage(it.id)?.knowledgeReferences }.orEmpty()
            snapshot = ReplySnapshot(body, references)
        }
        return requireNotNull(snapshot)
    }

    private data class ReplySnapshot(
        val body: String,
        val references: List<KnowledgeReference>,
    )

    private companion object {
        const val MAX_REPLY_BYTES = 800_000
    }
}
