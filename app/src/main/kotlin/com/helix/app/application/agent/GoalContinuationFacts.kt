package com.helix.app.application.agent

import com.helix.core.agent.Goal

/** Durable facts only. Reading these facts never grants or restores a live activation. */
internal fun interface GoalContinuationFactsReader {
    fun read(
        goalId: String,
        predecessorTurnId: String,
    ): GoalContinuationFacts
}

internal data class GoalContinuationFacts(
    val goal: Goal,
    val roundsUsed: Int,
    val turnState: String,
    val turnErrorCode: String?,
    val pauseRequestedAt: Long?,
    val hasPendingEdit: Boolean,
    val hasUnsettledCalls: Boolean,
    val hasOpenRuns: Boolean,
    val hasDeniedCalls: Boolean,
    val predecessorRunFinished: Boolean,
    val predecessorRunOutcome: String?,
    val predecessorIsLatestRun: Boolean,
)
