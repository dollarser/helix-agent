package com.helix.app.engine

import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.HelixError
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Ends recovery without rewriting any original executor result, review decision, or ownership lease. */
internal class AutomaticRecoverySettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    /** Runs inside the caller's transaction; the reducer owns every Goal terminal transition. */
    private fun finishGoal(
        runId: String,
        reason: String,
        now: Long,
    ) {
        GoalUsageReservations(storage).recoverRun(runId, now)
        val run = storage.goalRuns.resolve(runId)
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
        if (goal.state in setOf("COMPLETED", "FAILED", "CANCELLED") ||
            storage.goalRuns.listOpenByGoal(goal.id).isNotEmpty()
        ) {
            return
        }
        val runtime = goal.toRuntimeGoal()
        val error = HelixError(ErrorCode.EXECUTION, "Recovery ended: $reason", false, emptyMap(), runtime.correlationId)
        val ended = GoalReducer.reduce(runtime, GoalEvent.RecoveryEnded(error))
        check(!ended.ignored) { "Recovery closure requires a previously running Goal" }
        storage.goals.updateGoal(ended.state.toStoredGoal())
    }

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
            storage.goalTurnBindings.byTurn(parentId)?.let { finishGoal(it.runId, reason, now) }
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
                kotlinx.serialization.json
                    .JsonObject(
                        mapOf("reason" to kotlinx.serialization.json.JsonPrimitive(reason)),
                    ).toString(),
                now,
            )
        }
    }
}
