package com.helix.feature.conversation.application

import com.helix.core.model.TurnState

/** Small use-case facts, not persisted rows or a second execution owner. */
data class ConversationTurnStatus(
    val id: String,
    val sessionId: String,
    val state: TurnState,
    val modelStep: Int,
)

data class ConversationRetrySource(
    val inputTurnId: String,
    val continueResults: Boolean,
)

data class ConversationHistoryFocus(
    val messageId: String,
    val through: Long,
)

data class ConversationSteeringTarget(
    val state: TurnState,
    val providerSnapshot: String?,
)

enum class MessageRevisionAvailability { READY, TARGET_CHANGED, BUSY }

interface ConversationTurnHistory {
    fun turn(turnId: String): ConversationTurnStatus?

    fun visibleObservationTarget(turnId: String): ConversationTurnStatus?

    fun latest(sessionId: String): ConversationTurnStatus?

    fun latestVisible(sessionId: String): ConversationTurnStatus?

    fun recoveryTurnIds(): List<String>

    fun revisionAvailability(
        sessionId: String,
        messageId: String,
    ): MessageRevisionAvailability

    fun retrySource(
        sessionId: String,
        targetTurnId: String,
    ): ConversationRetrySource?

    fun regenerateSource(
        sessionId: String,
        assistantMessageId: String,
    ): String?

    fun locate(
        sessionId: String,
        query: String,
        before: Long?,
        after: Long?,
    ): ConversationHistoryFocus?

    fun steeringTarget(turnId: String): ConversationSteeringTarget?
}
