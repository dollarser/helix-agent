package com.helix.core.agent

import com.helix.core.model.GoalState

/**
 * Result of one [GoalReducer.reduce] step. `ignored = true` means the event is not applicable
 * in the current goal state; the state is returned unchanged and no effects are produced.
 */
data class GoalStep(
    val state: Goal,
    val effects: List<GoalEffect> = emptyList(),
    val ignored: Boolean = false,
) {
    companion object {
        fun unchanged(state: Goal): GoalStep = GoalStep(state, emptyList(), true)
    }
}

/**
 * Pure reducer for persistent Goals: `state + event -> state/effects`
 * (modes doc sections 6.1/6.2, architecture doc section 5.3).
 *
 * Design decisions (recorded in the HXA-013 completion record):
 * - A run (created by an explicit `Continued`) contains wakes. A wake ends by: normal Turn
 *   completion (`RunFinished` -> PAUSED, awaiting the next explicit wake), budget exhaustion
 *   (`WakeUsageReported` -> PAUSED with `BudgetExhausted(limit)`), input need (->
 *   INPUT_REQUIRED), or failure (`WakeFailed`: retry within budget stays RUNNING, otherwise
 *   FAILED). PAUSED is the single durable "awaiting user" state: between wakes, after
 *   exhaustion, and after process death.
 * - `WakeFailed`: a retryable failure consumes one of `maxRetries` and keeps the goal RUNNING
 *   with a `RetryWake` effect; a non-retryable failure, or retry exhaustion, fails the goal.
 *   (The `GoalState` machine has no RUNNING -> RUNNING edge, so retries are wake-level: the
 *   goal state stays RUNNING while the coordinator re-tries the wake.)
 * - `CompleteRequested` is honored only when every criterion carries verifier evidence.
 * - Process death parks RUNNING in PAUSED (durable park, `GoalState.stateAfterProcessDeath`);
 *   the checkpoint reminder survives the park because tapping it is a legitimate wake source.
 *   Reminders are cancelled on INPUT_REQUIRED/COMPLETED/FAILED/CANCELLED.
 * - The goal never grants permission: Act/Goal share the same per-call Policy/approval rules;
 *   this reducer only schedules work and accounts budgets.
 */
@Suppress("TooManyFunctions")
object GoalReducer {
    fun reduce(
        state: Goal,
        event: GoalEvent,
    ): GoalStep =
        when (event) {
            is GoalEvent.Ready -> onReady(state, event)
            is GoalEvent.Continued -> onContinued(state, event)
            is GoalEvent.WakeUsageReported -> onWakeUsage(state, event)
            is GoalEvent.WakeFailed -> onWakeFailed(state, event)
            GoalEvent.RunFinished -> onRunFinished(state)
            is GoalEvent.CriterionSatisfied -> onCriterionSatisfied(state, event)
            is GoalEvent.CheckpointScheduled -> onCheckpointScheduled(state, event)
            is GoalEvent.InputRequired -> onInputRequired(state, event)
            is GoalEvent.BudgetsUpdated -> onBudgetsUpdated(state, event)
            GoalEvent.CompleteRequested -> onCompleteRequested(state)
            GoalEvent.Cancelled -> onCancelled(state)
        }

    /**
     * Maps a goal across process death: RUNNING parks in PAUSED, all other states are durable
     * and unchanged. The in-flight wake's duration is not accounted (the coordinator never saw
     * its end); the checkpoint reminder survives because the notification is a wake source.
     */
    fun afterProcessDeath(state: Goal): Goal {
        val parked = state.state.stateAfterProcessDeath()
        if (parked == state.state) return state
        return state.copy(state = parked, currentWakeMillis = 0L)
    }

    private fun onReady(
        state: Goal,
        event: GoalEvent.Ready,
    ): GoalStep {
        if (state.state != GoalState.DRAFT) return GoalStep.unchanged(state)
        val next = state.copy(state = GoalState.READY, planId = event.planId, planHash = event.planHash)
        return step(state, next)
    }

    private fun onContinued(
        state: Goal,
        event: GoalEvent.Continued,
    ): GoalStep {
        val resumable = state.state in setOf(GoalState.READY, GoalState.PAUSED, GoalState.INPUT_REQUIRED)
        if (!resumable) return GoalStep.unchanged(state)
        val next =
            state.copy(
                state = GoalState.RUNNING,
                runCount = state.runCount + 1,
                lastWakeReason = event.wakeReason,
                currentWakeMillis = 0L,
            )
        val start =
            GoalEffect.StartRun(
                wakeReason = event.wakeReason,
                runNumber = next.runCount,
                remainingModelCalls = next.remainingModelCalls(),
                remainingToolCalls = next.remainingToolCalls(),
                remainingTotalTokens = next.remainingTotalTokens(),
                planHash = state.planHash,
            )
        return step(state, next, listOf(start))
    }

    private fun onWakeUsage(
        state: Goal,
        event: GoalEvent.WakeUsageReported,
    ): GoalStep {
        if (state.state != GoalState.RUNNING) return GoalStep.unchanged(state)
        val next =
            state.copy(
                modelCalls = state.modelCalls + event.modelCalls,
                toolCalls = state.toolCalls + event.toolCalls,
                totalTokens = state.totalTokens + event.tokens,
                runTimeMillis = state.runTimeMillis + event.wakeDurationMillis,
                currentWakeMillis = state.currentWakeMillis + event.wakeDurationMillis,
            )
        val exhausted = firstExhaustedLimit(next)
        return if (exhausted != null) {
            val parked = next.copy(state = GoalState.PAUSED, currentWakeMillis = 0L)
            step(next, parked, listOf(GoalEffect.BudgetExhausted(exhausted)))
        } else {
            step(state, next)
        }
    }

    private fun onWakeFailed(
        state: Goal,
        event: GoalEvent.WakeFailed,
    ): GoalStep {
        if (state.state != GoalState.RUNNING) return GoalStep.unchanged(state)
        val retryable = event.error.retryable && state.retries + 1 <= state.budgets.maxRetries
        return if (retryable) {
            val next = state.copy(retries = state.retries + 1, currentWakeMillis = 0L)
            step(state, next, listOf(GoalEffect.RetryWake(next.retries)))
        } else {
            val next = state.copy(state = GoalState.FAILED, error = event.error, currentWakeMillis = 0L)
            step(state, next, listOf(GoalEffect.GoalFailed(event.error)))
        }
    }

    private fun onRunFinished(state: Goal): GoalStep {
        if (state.state != GoalState.RUNNING) return GoalStep.unchanged(state)
        val checkpoint = state.nextCheckpoint
        val next = state.copy(state = GoalState.PAUSED, currentWakeMillis = 0L)
        val effects =
            if (checkpoint != null) {
                listOf(GoalEffect.RunFinished, GoalEffect.ScheduleCheckpointReminder(checkpoint))
            } else {
                listOf(GoalEffect.RunFinished)
            }
        return step(state, next, effects)
    }

    private fun onCriterionSatisfied(
        state: Goal,
        event: GoalEvent.CriterionSatisfied,
    ): GoalStep {
        val criterion = state.criteria.firstOrNull { it.id == event.criterionId }
        val satisfiable = state.state == GoalState.RUNNING && criterion != null && !criterion.isSatisfied
        if (!satisfiable) return GoalStep.unchanged(state)
        val next =
            state.copy(
                criteria =
                    state.criteria.map { if (it.id == event.criterionId) it.withEvidence(event.evidence) else it },
            )
        return step(state, next)
    }

    private fun onCheckpointScheduled(
        state: Goal,
        event: GoalEvent.CheckpointScheduled,
    ): GoalStep {
        if (state.state != GoalState.RUNNING) return GoalStep.unchanged(state)
        val next = state.copy(nextCheckpoint = event.checkpoint)
        return step(state, next, listOf(GoalEffect.ScheduleCheckpointReminder(event.checkpoint)))
    }

    private fun onInputRequired(
        state: Goal,
        event: GoalEvent.InputRequired,
    ): GoalStep {
        if (state.state != GoalState.RUNNING) return GoalStep.unchanged(state)
        val next = state.copy(state = GoalState.INPUT_REQUIRED, currentWakeMillis = 0L)
        return step(state, next, listOf(GoalEffect.ShowInputRequired(event.reason), GoalEffect.ReminderCancelled))
    }

    private fun onBudgetsUpdated(
        state: Goal,
        event: GoalEvent.BudgetsUpdated,
    ): GoalStep {
        val parked = state.state == GoalState.PAUSED || state.state == GoalState.INPUT_REQUIRED
        if (!parked) return GoalStep.unchanged(state)
        return step(state, state.copy(budgets = event.budgets))
    }

    private fun onCompleteRequested(state: Goal): GoalStep {
        val completable = state.state == GoalState.RUNNING && state.unsatisfiedCriteria.isEmpty()
        if (!completable) return GoalStep.unchanged(state)
        val next =
            state.copy(
                state = GoalState.COMPLETED,
                finishReason = "completed",
                currentWakeMillis = 0L,
                nextCheckpoint = null,
            )
        return step(state, next, listOf(GoalEffect.GoalCompleted, GoalEffect.ReminderCancelled))
    }

    private fun onCancelled(state: Goal): GoalStep {
        if (state.isTerminal) return GoalStep.unchanged(state)
        val next =
            state.copy(
                state = GoalState.CANCELLED,
                finishReason = "cancelled",
                currentWakeMillis = 0L,
                nextCheckpoint = null,
            )
        return step(state, next, listOf(GoalEffect.GoalCancelled, GoalEffect.ReminderCancelled))
    }

    /** First goal-lifetime budget that `goal` exceeds, in a fixed check order, or null. */
    private fun firstExhaustedLimit(goal: Goal): String? =
        when {
            goal.modelCalls > goal.budgets.maxModelCalls -> "maxModelCalls"
            goal.toolCalls > goal.budgets.maxToolCalls -> "maxToolCalls"
            goal.totalTokens > goal.budgets.maxTotalTokens -> "maxTotalTokens"
            goal.currentWakeMillis > goal.budgets.maxWakeDurationMillis -> "maxWakeDurationMillis"
            goal.runTimeMillis > goal.budgets.maxDurationMillis -> "maxDurationMillis"
            else -> null
        }

    /** Records the step, enforcing the GoalState transition rules and state invariants. */
    private fun step(
        prev: Goal,
        next: Goal,
        effects: List<GoalEffect> = emptyList(),
    ): GoalStep {
        if (prev.state != next.state) {
            require(prev.state.canTransitionTo(next.state)) {
                "illegal goal transition ${prev.state} -> ${next.state}"
            }
        }
        verify(next)
        return GoalStep(next, effects)
    }

    private fun verify(goal: Goal) {
        val state = goal.state
        if (state == GoalState.COMPLETED) {
            require(goal.error == null && goal.finishReason == "completed") {
                "COMPLETED goal requires finishReason=completed and no error"
            }
            require(goal.unsatisfiedCriteria.isEmpty()) {
                "COMPLETED goal requires verifier evidence for every criterion"
            }
        }
        if (state == GoalState.FAILED) {
            require(goal.error != null) { "FAILED goal requires an error" }
        }
        if (state == GoalState.CANCELLED) {
            require(goal.error == null && goal.finishReason == "cancelled") {
                "CANCELLED goal requires finishReason=cancelled and no error"
            }
        }
        if (!state.isTerminal) {
            require(goal.error == null && goal.finishReason == null) {
                "non-terminal goal must not carry finalization fields"
            }
        }
        if (state == GoalState.DRAFT || state == GoalState.READY) {
            require(goal.runCount == 0) { "runs only start from READY/PAUSED/INPUT_REQUIRED" }
        }
        if (state != GoalState.RUNNING) {
            require(goal.currentWakeMillis == 0L) { "currentWakeMillis is only valid in RUNNING" }
        }
        val checkpointable = state in setOf(GoalState.RUNNING, GoalState.PAUSED, GoalState.INPUT_REQUIRED)
        if (goal.nextCheckpoint != null) {
            require(checkpointable) { "nextCheckpoint is only valid in RUNNING/PAUSED/INPUT_REQUIRED" }
        }
    }
}
