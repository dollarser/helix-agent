package com.helix.app.goal

/**
 * Pure payload and text construction for the Goal checkpoint reminder. Kept free of Android
 * APIs so it is unit-testable on the JVM; the worker and scheduler are the only Android
 * surfaces.
 */
internal object GoalReminderPayload {
    const val KEY_GOAL_ID = "goal_id"
    const val KEY_OBJECTIVE = "objective"
    const val CHANNEL_ID = "goal_reminders"
    const val CHANNEL_NAME = "Goal reminders"
    const val UNIQUE_WORK_PREFIX = "goal-reminder-"
    private const val MAX_OBJECTIVE_IN_TEXT = 128

    /**
     * Reminder text. The UI must never present a checkpoint as an exact timer (architecture
     * doc section 5.1: WorkManager reminders may be delayed by Doze/force-stop), so the text
     * never contains a time or duration - only an invitation to continue.
     */
    fun text(objective: String): String {
        val trimmed = objective.trim().take(MAX_OBJECTIVE_IN_TEXT)
        return if (trimmed.isEmpty()) {
            "Goal checkpoint - open Helix to continue."
        } else {
            "Goal checkpoint: $trimmed - open Helix to continue."
        }
    }

    fun uniqueWorkName(goalId: String): String = UNIQUE_WORK_PREFIX + goalId
}
