package com.helix.app.chat

import com.helix.core.storage.HelixStorage

/** Counts admitted automatic prompts, excluding human turns and recovery inspections. */
internal class GoalRoundAllowance(
    private val storage: HelixStorage,
) {
    fun used(goalId: String): Int {
        val automatic =
            storage.goalRuns
                .listByGoal(goalId)
                .filter { it.wakeReason == "FOREGROUND_CONTINUATION" }
                .map { it.id }
                .toSet()
        return storage.goalTurnBindings.turnsForGoal(goalId).count { id ->
            val turn = storage.turns.resolve(id)
            turn.recoveryFromTurnId == null && storage.goalTurnBindings.byTurn(id)?.runId in automatic
        }
    }

    fun available(goalId: String): Boolean =
        used(goalId) <
            storage.goals
                .resolve(goalId)
                .budgets.maxGoalRounds

    /** Called after settlement or before admission, never ends an in-flight round. */
    fun blockIfExhausted(goalId: String): Boolean {
        var blocked = false
        storage.withTransaction {
            val goal = storage.goals.resolve(goalId)
            val settled =
                storage.goalRuns.listOpenByGoal(goalId).isEmpty() && !storage.goalTurnBindings.hasUnsettledCalls(goalId)
            if (!available(goalId) && goal.state in setOf("READY", "PAUSED", "INPUT_REQUIRED") && settled) {
                storage.goals.updateGoal(goal.copy(state = "BLOCKED", finishReason = "BLOCKED(round-limit)"))
                blocked = true
            }
        }
        return blocked
    }
}
