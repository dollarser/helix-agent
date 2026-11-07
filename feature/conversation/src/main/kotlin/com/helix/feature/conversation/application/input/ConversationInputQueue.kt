package com.helix.feature.conversation.application.input

import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.feature.conversation.input.PendingInputView
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** UI commands retain the displayed revision and return the application admission result. */
interface ConversationInputQueue {
    fun pendingInputViews(sessionId: String): Deferred<List<PendingInputView>>

    fun readSessionInput(inputId: String): Deferred<String?>

    fun observeSessionInputDelivery(sessionId: String): Flow<Long>

    fun withdrawSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): Deferred<Boolean>

    fun editSessionInput(
        inputId: String,
        expectedRevision: Long,
        text: String,
    ): Deferred<Boolean>

    fun sendQueuedInputNow(
        inputId: String,
        expectedRevision: Long,
        expectedTurnId: String,
    ): Deferred<Boolean>

    fun resumeSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): Deferred<ChatSubmissionOutcome>
}
