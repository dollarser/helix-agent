package com.helix.feature.conversation.application.recovery

import com.helix.core.agent.SubmitTurnCommand

/** Only queue arbitration facts, read together; accepted content and Room ordering remain in storage. */
data class QueuedSessionWork(
    val inputId: String,
    val sessionId: String,
    val revision: Long,
    val pending: Boolean,
    val blockedReason: String?,
)

data class SessionWorkSnapshot(
    val stopped: Boolean,
    val queue: QueuedSessionWork?,
    val hasPending: Boolean,
)

data class TerminalWorkFacts(
    val goalBound: Boolean,
    val userStopped: Boolean,
    val continuationAllowed: Boolean,
)

interface SessionWorkStore {
    fun snapshot(sessionId: String): SessionWorkSnapshot

    fun terminalFacts(
        sessionId: String,
        turnId: String,
    ): TerminalWorkFacts

    fun parkAfterFailure(
        sessionId: String,
        at: Long,
    )
}

/** The existing live activation owner, not a second execution engine. */
interface GoalContinuationHandoff {
    fun disarm(sessionId: String)

    fun reserveUserHandoff(
        sessionId: String,
        turnId: String,
    )

    fun reserveEligibleHandoff(
        sessionId: String,
        ownerTurnId: String,
    )

    fun handoffOwner(sessionId: String): String?

    fun resumeEligible(sessionId: String): SubmitTurnCommand?

    fun finishHandoff(
        sessionId: String,
        previousTurnId: String,
    )
}
