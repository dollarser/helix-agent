package com.helix.app.goal

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.helix.core.agent.Checkpoint
import com.helix.core.agent.ReminderPlan
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels deferrable Goal checkpoint reminders via WorkManager (architecture
 * doc section 5.1): a single deferrable one-time work per goal, replaced on reschedule.
 * Doze, force-stop and system scheduling may delay or drop the work; the Goal reducer treats
 * the reminder as an optional wake source, never as a timer.
 *
 * The agent runtime consumes `GoalEffect.ScheduleCheckpointReminder`/`ReminderCancelled`
 * through this facade; that wiring lands with the GoalFlow (M2+ agent runtime — HXA-015
 * delivered the recovery coordinator, not the wake/run loop that emits these effects).
 */
class GoalReminderScheduler(
    private val workManager: WorkManager,
) {
    /**
     * Schedules (or replaces) the reminder for [goalId] near [checkpoint]. [nowEpochMillis] is
     * the coordinator's clock; a checkpoint already in the past schedules immediately
     * (deferral recovery, see [ReminderPlan]).
     */
    fun scheduleReminder(
        goalId: String,
        objective: String,
        checkpoint: Checkpoint,
        nowEpochMillis: Long,
    ) {
        val plan = ReminderPlan.forCheckpoint(nowEpochMillis, checkpoint)
        if (plan.skip) return
        val request =
            OneTimeWorkRequestBuilder<GoalReminderWorker>()
                .setInputData(
                    workDataOf(
                        GoalReminderPayload.KEY_GOAL_ID to goalId,
                        GoalReminderPayload.KEY_OBJECTIVE to objective,
                    ),
                ).setInitialDelay(plan.delayMillis, TimeUnit.MILLISECONDS)
                .build()
        workManager.enqueueUniqueWork(GoalReminderPayload.uniqueWorkName(goalId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels the pending reminder for [goalId] (goal completed/failed/cancelled/input-required). */
    fun cancelReminder(goalId: String) {
        workManager.cancelUniqueWork(GoalReminderPayload.uniqueWorkName(goalId))
    }

    companion object {
        fun create(context: Context): GoalReminderScheduler = GoalReminderScheduler(WorkManager.getInstance(context))
    }
}
