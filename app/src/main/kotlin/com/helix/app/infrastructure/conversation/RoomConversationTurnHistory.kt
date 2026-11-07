package com.helix.app.infrastructure.conversation

import com.helix.app.chat.BudgetContinuation
import com.helix.app.chat.RetryMessageSource
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.feature.conversation.application.ConversationHistoryFocus
import com.helix.feature.conversation.application.ConversationRetrySource
import com.helix.feature.conversation.application.ConversationSteeringTarget
import com.helix.feature.conversation.application.ConversationTurnHistory
import com.helix.feature.conversation.application.ConversationTurnStatus
import com.helix.feature.conversation.application.MessageRevisionAvailability

/** Every multi-table decision is made from one Room snapshot. Runtime admission rechecks mutations. */
@Suppress("TooManyFunctions") // One history port keeps cross-table decisions within owning Room snapshots.
internal class RoomConversationTurnHistory(
    private val storage: HelixStorage,
) : ConversationTurnHistory {
    override fun turn(turnId: String): ConversationTurnStatus? = storage.turns.find(turnId)?.status()

    override fun visibleObservationTarget(turnId: String): ConversationTurnStatus? {
        var result: ConversationTurnStatus? = null
        storage.withTransaction {
            val turn = storage.turns.find(turnId) ?: return@withTransaction
            if (storage.turns.latestVisible(turn.sessionId)?.id == turnId) result = turn.status()
        }
        return result
    }

    override fun latest(sessionId: String): ConversationTurnStatus? =
        storage.turns
            .listBySession(sessionId)
            .lastOrNull()
            ?.status()

    override fun latestVisible(sessionId: String): ConversationTurnStatus? =
        storage.turns.latestVisible(sessionId)?.status()

    override fun recoveryTurnIds(): List<String> = storage.turns.latestForUnarchivedSessions().map { it.id }

    override fun revisionAvailability(
        sessionId: String,
        messageId: String,
    ): MessageRevisionAvailability {
        var result = MessageRevisionAvailability.TARGET_CHANGED
        storage.withTransaction {
            if (storage.messages.latestUser(sessionId)?.id != messageId) return@withTransaction
            result =
                if (storage.turns.listBySession(sessionId).any { !TurnState.valueOf(it.state).isTerminal }) {
                    MessageRevisionAvailability.BUSY
                } else {
                    MessageRevisionAvailability.READY
                }
        }
        return result
    }

    override fun retrySource(
        sessionId: String,
        targetTurnId: String,
    ): ConversationRetrySource? {
        var result: ConversationRetrySource? = null
        storage.withTransaction {
            val target = storage.turns.find(targetTurnId) ?: return@withTransaction
            if (target.sessionId != sessionId) return@withTransaction
            val continuation = BudgetContinuation.eligible(storage, target)
            if (BudgetContinuation.blocksRetry(storage, target, continuation)) return@withTransaction
            result = inputTurn(sessionId, targetTurnId)?.let { ConversationRetrySource(it, continuation) }
        }
        return result
    }

    override fun regenerateSource(
        sessionId: String,
        assistantMessageId: String,
    ): String? {
        var result: String? = null
        storage.withTransaction {
            val assistant = storage.messages.find(assistantMessageId) ?: return@withTransaction
            if (assistant.sessionId != sessionId || assistant.role != "ASSISTANT" ||
                assistant.supersededBy != null
            ) {
                return@withTransaction
            }
            result = assistant.turnId?.let { inputTurn(sessionId, it) }
        }
        return result
    }

    private fun inputTurn(
        sessionId: String,
        targetTurnId: String,
    ): String? =
        RetryMessageSource.resolve(
            targetTurnId,
            storage.turns.listBySession(sessionId).map { it.id },
            storage.messages
                .listBySession(sessionId)
                .filter { it.role == "USER" }
                .mapNotNull { it.turnId }
                .toSet(),
        )

    override fun locate(
        sessionId: String,
        query: String,
        before: Long?,
        after: Long?,
    ): ConversationHistoryFocus? {
        var focus: ConversationHistoryFocus? = null
        storage.withTransaction {
            val match = storage.sessionSearch.locateMessage(sessionId, query, before, after) ?: return@withTransaction
            val following = storage.messages.pageAfter(sessionId, match.sequence, 50)
            focus =
                ConversationHistoryFocus(
                    match.id,
                    if (following.size <
                        50
                    ) {
                        Long.MAX_VALUE
                    } else {
                        following.last().sequence
                    },
                )
        }
        return focus
    }

    override fun steeringTarget(turnId: String): ConversationSteeringTarget? {
        var result: ConversationSteeringTarget? = null
        storage.withTransaction {
            val turn = storage.turns.find(turnId) ?: return@withTransaction
            result =
                ConversationSteeringTarget(
                    TurnState.valueOf(turn.state),
                    storage.modelCalls
                        .listByTurn(turnId)
                        .firstOrNull()
                        ?.providerSnapshot,
                )
        }
        return result
    }

    private fun TurnEntity.status() = ConversationTurnStatus(id, sessionId, TurnState.valueOf(state), stepCount)
}
