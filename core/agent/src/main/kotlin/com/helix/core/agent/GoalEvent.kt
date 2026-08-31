package com.helix.core.agent

import com.helix.core.model.GoalBudgets
import com.helix.core.model.HelixError
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256

/**
 * Events of the Goal reducer. The coordinator is the only producer; every event is validated
 * against the goal state and phase by [GoalReducer] (stale/illegal events are ignored or
 * rejected, never guessed at).
 */
sealed interface GoalEvent {
    /**
     * User finalized the draft. Optionally attaches the plan the goal was created from; when
     * present, the plan's hash is recorded so later runs can reference the exact plan version
     * (modes doc section 6.1: "the original plan hash is written into the run record").
     */
    data class Ready(
        val planId: PlanId?,
        val planHash: Sha256?,
    ) : GoalEvent {
        init {
            require((planId == null) == (planHash == null)) {
                "planId and planHash must be set together"
            }
        }
    }

    /**
     * Explicit user continue - the only wake source of the first version (opening the goal or
     * tapping the checkpoint notification). Creates a new run from READY, PAUSED or
     * INPUT_REQUIRED.
     */
    data class Continued(
        val wakeReason: GoalWakeReason,
    ) : GoalEvent

    /**
     * The wake's Turn finished; the coordinator reports the aggregated usage of that wake.
     * The reducer accumulates it into the goal-lifetime budget and parks the goal in PAUSED
     * when any budget is exhausted.
     */
    data class WakeUsageReported(
        val modelCalls: Int,
        val toolCalls: Int,
        val tokens: Long,
        val wakeDurationMillis: Long,
    ) : GoalEvent {
        init {
            require(modelCalls >= 0 && toolCalls >= 0) { "call counts must be >= 0" }
            require(tokens >= 0) { "tokens must be >= 0" }
            require(wakeDurationMillis >= 0) { "wakeDurationMillis must be >= 0" }
        }
    }

    /**
     * The wake's run failed. Retryable failures consume [com.helix.core.model.GoalBudgets.maxRetries]
     * and the goal stays RUNNING (a new wake of the same run); otherwise the goal fails.
     */
    data class WakeFailed(
        val error: HelixError,
    ) : GoalEvent

    /**
     * The wake's Turn ended normally without completing the goal or exhausting a budget.
     * The goal parks in PAUSED - the same durable state as budget exhaustion - where an
     * explicit user continue (USER_OPEN or the checkpoint notification) starts the next wake.
     * A retry (retryable WakeFailed within budget) does not park: it is a new wake of the
     * same run.
     */
    data object RunFinished : GoalEvent

    /**
     * A verifier produced real evidence (ToolResult/Artifact) for one criterion. Only this
     * event can satisfy a criterion - the model's own claims never do.
     */
    data class CriterionSatisfied(
        val criterionId: String,
        val evidence: CriterionEvidence,
    ) : GoalEvent

    /** Sets the goal's next checkpoint and asks the coordinator to schedule its reminder. */
    data class CheckpointScheduled(
        val checkpoint: Checkpoint,
    ) : GoalEvent

    /**
     * The goal needs the user: permission revoked, target package changed or side effects
     * unclear (modes doc section 6.1). The active run stops; resume is explicit.
     */
    data class InputRequired(
        val reason: String,
    ) : GoalEvent {
        init {
            require(reason.isNotBlank()) { "reason must not be blank" }
            require(reason.length <= MAX_REASON_LENGTH) {
                "reason must be <= $MAX_REASON_LENGTH characters"
            }
        }
    }

    /** User changed the budget while the goal is parked (PAUSED/INPUT_REQUIRED). */
    data class BudgetsUpdated(
        val budgets: GoalBudgets,
    ) : GoalEvent

    /**
     * All acceptance criteria carry verifier evidence and the user (or verifier flow) asks to
     * finish. The reducer re-checks that every criterion is satisfied.
     */
    data object CompleteRequested : GoalEvent

    /** Explicit user cancellation from any non-terminal state. */
    data object Cancelled : GoalEvent

    companion object {
        const val MAX_REASON_LENGTH = 512
    }
}
