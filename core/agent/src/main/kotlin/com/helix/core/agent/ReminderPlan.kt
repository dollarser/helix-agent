package com.helix.core.agent

import com.helix.core.model.GoalState

/**
 * Pure scheduling decision for the deferrable WorkManager checkpoint reminder
 * (architecture doc section 5.1: WorkManager may only send deferrable reminders near
 * `nextCheckpoint`; Doze, force-stop and system scheduling may delay or cancel it, and the UI
 * must never present the checkpoint as an exact timer).
 *
 * The coordinator applies the plan to WorkManager: a `delayMillis` of 0 means "schedule
 * immediately" (the checkpoint is already in the past, e.g. after a deferral); `skip` means
 * there is nothing to schedule (no checkpoint, or the goal is in a state that no longer wants
 * reminders).
 */
data class ReminderPlan(
    val skip: Boolean,
    val delayMillis: Long,
) {
    companion object {
        const val SCHEDULE_NOW_MILLIS = 0L

        fun forCheckpoint(
            nowEpochMillis: Long,
            checkpoint: Checkpoint?,
        ): ReminderPlan {
            val delay = checkpoint?.atEpochMillis?.minus(nowEpochMillis) ?: return ReminderPlan(true, 0L)
            return ReminderPlan(false, maxOf(delay, SCHEDULE_NOW_MILLIS))
        }

        /**
         * Decides the reminder for a goal state: only goals that are actively parked or running
         * want a reminder (RUNNING, PAUSED, INPUT_REQUIRED are checkpointable); a terminal or
         * not-yet-running goal has nothing to remind.
         */
        fun forGoal(
            nowEpochMillis: Long,
            state: GoalState,
            checkpoint: Checkpoint?,
        ): ReminderPlan {
            val wantsReminder = state in setOf(GoalState.RUNNING, GoalState.PAUSED, GoalState.INPUT_REQUIRED)
            if (!wantsReminder) return ReminderPlan(true, 0L)
            return forCheckpoint(nowEpochMillis, checkpoint)
        }
    }
}
