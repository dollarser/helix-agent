package com.helix.app.engine

import com.helix.core.model.TurnBudgets
import com.helix.core.storage.entity.TurnEntity

internal object AutomaticRecoveryPolicy {
    fun requestId(parent: String) = "auto-recovery:$parent"

    fun isInspection(turn: TurnEntity): Boolean =
        turn.recoveryFromTurnId?.let { turn.clientRequestId == requestId(it) } == true

    fun eligible(turn: TurnEntity): Boolean =
        turn.state in setOf("NEEDS_REVIEW", "INTERRUPTED") && turn.pauseRequestedAt == null &&
            turn.errorCode != "USER_STOP" && !isInspection(turn)

    fun limits(
        snapshot: TurnRuntimeSnapshot,
        boundGoal: Boolean,
    ): TurnBudgets? {
        val original = snapshot.control.budgets
        val models = if (boundGoal) original.maxModelCalls else original.maxModelCalls - snapshot.consumedModelCalls
        val tokens = if (boundGoal) original.maxTotalTokens else original.maxTotalTokens - snapshot.consumedTokens
        val steps = if (boundGoal) original.maxSteps else original.maxSteps - snapshot.admittedToolRounds
        if (models <= 0 || tokens <= 0 || steps <= 0) return null
        return original.copy(
            maxModelCalls = minOf(models, 4),
            maxSteps = minOf(steps, 8),
            maxInputTokens = minOf(original.maxInputTokens, tokens),
            maxOutputTokens = minOf(original.maxOutputTokens, tokens),
            maxTotalTokens = tokens,
        )
    }
}
