package com.helix.app.engine

import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Ends recovery without rewriting any original executor result, review decision, or ownership lease. */
internal class AutomaticRecoverySettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    fun finish(
        parentId: String,
        reason: String,
        notice: String?,
    ) {
        storage.withTransaction {
            val parent = storage.turns.resolve(parentId)
            val key = "recovery-ended:$parentId"
            if (storage.auditEvents.listByCorrelation(parent.sessionId).any { it.id == key }) return@withTransaction
            val now = clock.now().toEpochMilli()
            if (parent.state == "NEEDS_REVIEW") {
                storage.turns.updateState(
                    parent,
                    TurnState.INTERRUPTED,
                    parent.stepCount,
                    now.coerceAtLeast(parent.startedAt),
                    reason,
                )
            }
            storage.goalTurnBindings.byTurn(parentId)?.let { binding ->
                GoalUsageReservations(storage).recoverRun(binding.runId, now)
                val run = storage.goalRuns.resolve(binding.runId)
                if (run.endedAt == null) {
                    storage.goalRuns.finish(
                        run,
                        reason,
                        now.coerceAtLeast(run.startedAt),
                        run.wakeDurationMillis ?: 0,
                        run.modelCalls,
                        run.toolCalls,
                        run.tokens,
                    )
                }
                val goal = storage.goals.resolve(run.goalId)
                if (goal.state !in setOf("COMPLETED", "FAILED", "CANCELLED") &&
                    storage.goalRuns.listOpenByGoal(goal.id).isEmpty()
                ) {
                    storage.goals.updateGoal(goal.copy(state = "FAILED", currentWakeMillis = 0))
                }
            }
            if (notice !=
                null
            ) {
                storage.messages.append(key, parent.sessionId, null, "SYSTEM", "RECOVERY_NOTICE", notice)
            }
            storage.auditEvents.append(
                key,
                parent.sessionId,
                "recovery.ended",
                "SYSTEM",
                "{\"reason\":\"$reason\"}",
                now,
            )
        }
    }
}
