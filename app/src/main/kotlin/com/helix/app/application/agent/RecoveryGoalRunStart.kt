package com.helix.app.application.agent

import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.app.infrastructure.agent.RoomTurnRoundStore
import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.agent.GoalEffect
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage

/** Called only by the Engine's admission transaction after its read-only recovery guard. */
internal class RecoveryGoalRunStart(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val ids: () -> String,
) {
    @Suppress("ReturnCount") // Original Goal identity, open-run and budget gates precede all admission.
    fun start(request: GoalTurnStart): StartedGoalTurn? {
        val predecessor = requireNotNull(request.turn.recoveryFromTurnId)
        val binding = storage.goalTurnBindings.byTurn(predecessor) ?: return null
        val original = storage.goalRuns.resolve(binding.runId)
        require(original.goalId == request.goalId)
        // Unknown allocations consume their reservation; do not refund them to fund inspection.
        GoalUsageReservations(storage).recoverRun(original.id, clock.now().toEpochMilli())
        val settled = storage.goalRuns.resolve(original.id)
        if (settled.endedAt == null) {
            storage.goalRuns.finish(
                settled,
                "RECOVERY_INSPECTION_REQUIRED",
                clock.now().toEpochMilli().coerceAtLeast(settled.startedAt),
                settled.wakeDurationMillis ?: 0,
                settled.modelCalls,
                settled.toolCalls,
                settled.tokens,
            )
        }
        if (storage.goalRuns.listOpenByGoal(request.goalId).isNotEmpty()) return null
        val goal = storage.goals.resolve(request.goalId).toRuntimeGoal()
        val step = GoalReducer.reduce(goal, GoalEvent.RecoveryInspection)
        if (step.effects.filterIsInstance<GoalEffect.StartRun>().singleOrNull() == null) return null
        val limits = request.turnLimits
        val runId = ids()
        storage.goals.updateGoal(step.state.toStoredGoal())
        storage.goalRuns.open(runId, request.goalId, request.wakeReason.name, clock.now().toEpochMilli())
        val coordinator = RoomTurnRoundStore.start(storage, clock, ids, request.turn.copy(goalRunId = runId))
        storage.auditEvents.append(
            ids(),
            request.turn.sessionId,
            "goal.recovery_started",
            "SYSTEM",
            "{}",
            clock.now().toEpochMilli(),
        )
        return StartedGoalTurn(runId, coordinator, limits)
    }
}
