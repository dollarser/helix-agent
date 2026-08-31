package com.helix.core.agent

import com.helix.core.model.HelixError
import com.helix.core.model.Sha256

/**
 * Side effects requested by the Goal reducer. The coordinator executes them (UI, WorkManager
 * scheduling, running a Turn). Effects never touch model/tool bodies themselves.
 */
sealed interface GoalEffect {
    /**
     * Start a new run (Turn) for this goal. The coordinator composes the per-turn
     * `TurnBudgets` from the remaining goal budget (stricter with user/provider limits, per
     * architecture doc section 5.3) and executes the Turn; usage comes back via
     * [GoalEvent.WakeUsageReported]/[GoalEvent.WakeFailed].
     */
    data class StartRun(
        val wakeReason: GoalWakeReason,
        val runNumber: Int,
        val remainingModelCalls: Int,
        val remainingToolCalls: Int,
        val remainingTotalTokens: Long,
        val planHash: Sha256?,
    ) : GoalEffect

    /** Schedule a deferrable WorkManager reminder near [checkpoint] (never exact-timer UI). */
    data class ScheduleCheckpointReminder(
        val checkpoint: Checkpoint,
    ) : GoalEffect

    /** Cancel any pending reminder for this goal. */
    data object ReminderCancelled : GoalEffect

    /** The wake failed retryably and a retry within budget is requested. */
    data class RetryWake(
        val attempt: Int,
    ) : GoalEffect

    /** Show the input-required state (permission revoked, target package changed, unclear side effects). */
    data class ShowInputRequired(
        val reason: String,
    ) : GoalEffect

    /** A budget was exhausted; the goal is parked in PAUSED until the user extends or cancels. */
    data class BudgetExhausted(
        val limit: String,
    ) : GoalEffect

    /**
     * The wake finished normally and the goal parked in PAUSED, waiting for the next wake.
     * If a checkpoint is set, the pending reminder is (re)scheduled - the notification is the
     * legitimate NOTIFICATION_ACTION wake source.
     */
    data object RunFinished : GoalEffect

    data object GoalCompleted : GoalEffect

    data class GoalFailed(
        val error: HelixError,
    ) : GoalEffect

    data object GoalCancelled : GoalEffect
}
