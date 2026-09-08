package com.helix.app.chat

import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalState
import com.helix.core.model.HelixError
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Settles only a durably terminal Turn. Called inside TurnCoordinator's terminal transaction. */
internal class GoalRunSettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun settle(
        turnId: String,
        verifyGoal: ((com.helix.core.agent.Goal, String) -> com.helix.core.agent.Goal)? = null,
    ) {
        storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId)
            if (binding != null) settleBoundTurn(turnId, binding.runId, verifyGoal)
        }
    }

    private fun settleBoundTurn(
        turnId: String,
        runId: String,
        verifyGoal: ((com.helix.core.agent.Goal, String) -> com.helix.core.agent.Goal)?,
    ) {
        val turn = storage.turns.resolve(turnId)
        val state = TurnState.valueOf(turn.state)
        require(state.isTerminal) { "Goal settlement requires a terminal Turn" }
        com.helix.app.recovery
            .GoalUsageReservations(storage)
            .recoverRun(runId, clock.now().toEpochMilli())
        val run = storage.goalRuns.resolve(runId)
        var goal = storage.goals.resolve(run.goalId).toRuntimeGoal()
        // Ledger budget closure and repeated terminal notifications must preserve the first outcome.
        if (run.endedAt != null || goal.state != GoalState.RUNNING) return
        val uncertain = storage.goalTurnBindings.hasUnsettledCalls(goal.id.value)
        val verified = state == TurnState.COMPLETED && !uncertain && verifyGoal != null
        if (verified) goal = requireNotNull(verifyGoal).invoke(goal, turnId)
        val (event, outcome) =
            if (verified && goal.unsatisfiedCriteria.isEmpty()) {
                GoalEvent.CompleteRequested to "COMPLETED"
            } else {
                decision(goal, state, turn.errorCode, uncertain)
            }
        val next = GoalReducer.reduce(goal, event)
        check(!next.ignored) { "Goal settlement was not applicable" }
        storage.goals.updateGoal(next.state.toStoredGoal())
        storage.goalRuns.finish(
            run,
            outcome,
            clock.now().toEpochMilli().coerceAtLeast(run.startedAt),
            run.wakeDurationMillis ?: 0L,
            run.modelCalls,
            run.toolCalls,
            run.tokens,
        )
        storage.auditEvents.append(
            idGenerator(),
            goal.correlationId.value,
            "goal.run_finished",
            "SYSTEM",
            """{"outcome":"$outcome","turnState":"${state.name}"}""",
            clock.now().toEpochMilli(),
        )
    }

    private fun decision(
        goal: com.helix.core.agent.Goal,
        state: TurnState,
        errorCode: String?,
        uncertain: Boolean,
    ): Pair<GoalEvent, String> =
        when {
            uncertain -> {
                GoalEvent.InputRequired("NEEDS_REVIEW") to "INPUT_REQUIRED(NEEDS_REVIEW)"
            }

            state == TurnState.CANCELLED -> {
                GoalEvent.Cancelled to "CANCELLED"
            }

            state == TurnState.COMPLETED -> {
                GoalEvent.RunFinished to "RUN_FINISHED"
            }

            errorCode == "GOAL_TIME_WINDOW_EXPIRED" -> {
                GoalEvent.RunFinished to "INTERRUPTED"
            }

            errorCode in TURN_LIMITS -> {
                GoalEvent.RunFinished to "RUN_FINISHED"
            }

            else -> {
                GoalEvent.WakeFailed(
                    HelixError(ErrorCode.EXECUTION, "Goal turn failed", false, emptyMap(), goal.correlationId),
                ) to "FAILED"
            }
        }

    private companion object {
        val TURN_LIMITS = setOf("MODEL_CALL_LIMIT", "TOKEN_BUDGET_LIMIT", "TOOL_STEP_LIMIT", "GOAL_BUDGET_LIMIT")
    }
}
