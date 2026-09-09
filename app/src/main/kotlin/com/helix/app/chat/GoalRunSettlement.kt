package com.helix.app.chat

import com.helix.app.goal.goalModelReport
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
    fun settle(turnId: String) {
        storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId)
            if (binding != null) settleBoundTurn(turnId, binding.runId)
        }
    }

    private fun settleBoundTurn(
        turnId: String,
        runId: String,
    ) {
        val turn = storage.turns.resolve(turnId)
        val state = TurnState.valueOf(turn.state)
        require(state.isTerminal) { "Goal settlement requires a terminal Turn" }
        com.helix.app.recovery
            .GoalUsageReservations(storage)
            .recoverRun(runId, clock.now().toEpochMilli())
        val run = storage.goalRuns.resolve(runId)
        val goal = storage.goals.resolve(run.goalId).toRuntimeGoal()
        // Ledger budget closure and repeated terminal notifications must preserve the first outcome.
        if (run.endedAt != null || goal.state != GoalState.RUNNING) return
        val uncertain = storage.goalTurnBindings.hasUnsettledCalls(goal.id.value)
        val paused = turn.pauseRequestedAt != null
        val report =
            if (state == TurnState.COMPLETED && !uncertain &&
                !paused
            ) {
                storage.goalModelReport(turnId)
            } else {
                null
            }
        val (event, outcome) =
            if (report?.status == "complete") {
                GoalEvent.CompleteRequested to "MODEL_COMPLETED"
            } else if (report?.status == "blocked") {
                GoalEvent.Blocked to "BLOCKED(MODEL_REPORTED)"
            } else if (!uncertain && paused) {
                GoalEvent.RunFinished to "USER_PAUSED"
            } else {
                decision(goal.correlationId, state, turn.errorCode, uncertain)
            }
        val next = GoalReducer.reduce(goal, event)
        check(!next.ignored) { "Goal settlement was not applicable" }
        val settledOutcome =
            if (next.state.state == GoalState.BLOCKED && !outcome.startsWith("BLOCKED(")) {
                "BUDGET_EXHAUSTED(remainingBudget)"
            } else {
                outcome
            }
        storage.goals.updateGoal(next.state.toStoredGoal())
        recordReport(goal, report)
        storage.goalRuns.finish(
            run,
            settledOutcome,
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
            """{"outcome":"$settledOutcome","turnState":"${state.name}"}""",
            clock.now().toEpochMilli(),
        )
    }

    private fun recordReport(
        goal: com.helix.core.agent.Goal,
        report: com.helix.app.goal.GoalModelReport?,
    ) {
        if (report != null) {
            storage.auditEvents.append(
                idGenerator(),
                goal.correlationId.value,
                "goal.model_report",
                "MODEL",
                """{"status":"${report.status}","toolCallId":"${report.callId}"}""",
                clock.now().toEpochMilli(),
            )
        }
    }

    private fun decision(
        correlationId: com.helix.core.model.CorrelationId,
        state: TurnState,
        errorCode: String?,
        uncertain: Boolean,
    ): Pair<GoalEvent, String> =
        when {
            uncertain -> {
                GoalEvent.Blocked to "BLOCKED(NEEDS_REVIEW)"
            }

            state == TurnState.CANCELLED -> {
                GoalEvent.Cancelled to "CANCELLED"
            }

            state == TurnState.COMPLETED -> {
                GoalEvent.RunFinished to "RUN_FINISHED"
            }

            errorCode == "CONTEXT_WINDOW_LIMIT" -> {
                GoalEvent.Blocked to "BLOCKED(CONTEXT_WINDOW_LIMIT)"
            }

            errorCode == "GOAL_TIME_WINDOW_EXPIRED" -> {
                GoalEvent.RunFinished to "INTERRUPTED"
            }

            errorCode in TURN_LIMITS -> {
                GoalEvent.RunFinished to "RUN_FINISHED"
            }

            else -> {
                GoalEvent.WakeFailed(
                    HelixError(ErrorCode.EXECUTION, "Goal turn failed", false, emptyMap(), correlationId),
                ) to "FAILED"
            }
        }

    private companion object {
        val TURN_LIMITS =
            setOf(
                "MODEL_CALL_LIMIT",
                "TOKEN_BUDGET_LIMIT",
                "TOOL_STEP_LIMIT",
                "GOAL_BUDGET_LIMIT",
                "CONTEXT_WINDOW_LIMIT",
            )
    }
}
