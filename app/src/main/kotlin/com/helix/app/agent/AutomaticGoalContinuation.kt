package com.helix.app.agent

/** Local attempt limits may yield to the same authorized Goal, never to a larger total budget. */
internal object AutomaticGoalContinuation {
    fun accepts(
        state: String,
        errorCode: String?,
        pauseRequestedAt: Long?,
    ): Boolean =
        pauseRequestedAt == null &&
            state == "COMPLETED" && errorCode == null
}
