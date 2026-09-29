package com.helix.app.agent

/** Local attempt limits may yield to the same authorized Goal, never to a larger total budget. */
internal object AutomaticGoalContinuation {
    private val localLimits =
        setOf("MODEL_CALL_LIMIT", "TOOL_STEP_LIMIT", "TURN_TOTAL_TOKEN_LIMIT", "TOKEN_BUDGET_LIMIT")

    fun accepts(
        state: String,
        errorCode: String?,
        pauseRequestedAt: Long?,
    ): Boolean =
        pauseRequestedAt == null &&
            (state == "COMPLETED" || (state == "FAILED" && errorCode in localLimits))
}
