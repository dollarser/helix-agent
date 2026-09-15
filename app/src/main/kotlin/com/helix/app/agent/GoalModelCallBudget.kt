package com.helix.app.agent

import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.agent.TokenEstimator
import com.helix.core.model.Clock
import com.helix.core.model.GoalState
import com.helix.core.model.ModelRequest
import com.helix.core.storage.HelixStorage

/** The production model boundary: only a durably bound Goal Turn spends Goal capacity. */
internal class GoalModelCallBudget(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    fun prepare(
        turnId: String,
        callId: String,
        request: ModelRequest,
    ): ModelRequest? {
        var admitted: ModelRequest? = null
        storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId)
            if (binding == null) {
                admitted = request
            } else {
                val run = storage.goalRuns.resolve(binding.runId)
                val goal = storage.goals.resolve(run.goalId)
                val held = storage.goalUsageReservations.pendingForRun(run.id).sumOf { it.reservedTokens }
                val input = TokenEstimator.estimateTokens(TurnBudgetTracker.requestSizeBytes(request))
                val output =
                    minOf(
                        request.maxOutputTokens ?: Long.MAX_VALUE,
                        goal.budgets.maxTotalTokens - goal.totalTokens - held - input,
                    )
                if (output > 0) {
                    val reservation =
                        GoalUsageReservations.Request(
                            reservationId(callId),
                            run.id,
                            GoalUsageReservations.Kind.MODEL,
                            input + output,
                        )
                    if (GoalUsageReservations(storage).reserve(reservation)) {
                        admitted =
                            request.copy(maxOutputTokens = output)
                    }
                }
            }
        }
        return admitted
    }

    /** Only a normally drained stream has locally known usage. Exceptions leave the reservation pending. */
    fun finish(
        turnId: String,
        callId: String,
        request: ModelRequest,
        stream: ModelStreamState,
    ) {
        storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId)
            if (binding != null) {
                val id = reservationId(callId)
                val reservation = requireNotNull(storage.goalUsageReservations.byId(id))
                require(reservation.runId == binding.runId)
                val tokens = ModelCallUsage.total(ModelCallUsage.account(callId, request, stream))
                GoalUsageReservations(storage).settle(id, tokens, 0, clock.now().toEpochMilli())
            }
        }
    }

    fun canContinue(turnId: String): Boolean {
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return true
        val run = storage.goalRuns.resolve(binding.runId)
        val goal = storage.goals.resolve(run.goalId)
        return run.endedAt == null && goal.state == GoalState.RUNNING.name &&
            goal.modelCalls < goal.budgets.maxModelCalls && goal.toolCalls < goal.budgets.maxToolCalls &&
            goal.totalTokens < goal.budgets.maxTotalTokens &&
            goal.currentWakeMillis < goal.budgets.maxWakeDurationMillis &&
            goal.runTimeMillis < goal.budgets.maxDurationMillis
    }

    private fun reservationId(callId: String): String = "goal-model:$callId"
}
