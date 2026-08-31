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
 *
 * The scheduling decision (work name, delay, payload) is a pure composition over
 * [ReminderPlan] and [GoalReminderPayload.uniqueWorkName], handed to a [ReminderEnqueuer]
 * seam so it is unit-testable on the JVM; the WorkManager REPLACE effect itself is
 * device-verified by GoalReminderTest.
 */
class GoalReminderScheduler(
    private val enqueuer: ReminderEnqueuer,
) {
    /**
     * Schedules (or replaces) the reminder for [goalId] near [checkpoint]. [nowEpochMillis] is
     * the coordinator's clock; a checkpoint already in the past schedules immediately
     * (deferral recovery, see [ReminderPlan]).
     */
    fun scheduleReminder(
        goalId: String,
        objective: String,
        checkpoint: Checkpoint?,
        nowEpochMillis: Long,
    ) {
        val plan = ReminderPlan.forCheckpoint(nowEpochMillis, checkpoint)
        if (plan.skip) return
        enqueuer.enqueueOrReplace(
            workName = GoalReminderPayload.uniqueWorkName(goalId),
            delayMillis = plan.delayMillis,
            goalId = goalId,
            objective = objective,
        )
    }

    /** Cancels the pending reminder for [goalId] (goal completed/failed/cancelled/input-required). */
    fun cancelReminder(goalId: String) {
        enqueuer.cancel(GoalReminderPayload.uniqueWorkName(goalId))
    }

    companion object {
        fun create(context: Context): GoalReminderScheduler =
            GoalReminderScheduler(WorkManagerReminderEnqueuer(WorkManager.getInstance(context)))
    }
}

/**
 * Minimal WorkManager queue seam: keeps the scheduling decision (unique work name, delay,
 * payload) testable on the JVM. The production implementation is a unique-work enqueue with
 * [ExistingWorkPolicy.REPLACE] — one reminder per goal, reschedule replaces instead of
 * stacking (HXA-013 invariant).
 */
interface ReminderEnqueuer {
    fun enqueueOrReplace(
        workName: String,
        delayMillis: Long,
        goalId: String,
        objective: String,
    )

    fun cancel(workName: String)
}

/** The production [ReminderEnqueuer]: a unique-work enqueue that always replaces. */
class WorkManagerReminderEnqueuer(
    private val workManager: WorkManager,
) : ReminderEnqueuer {
    override fun enqueueOrReplace(
        workName: String,
        delayMillis: Long,
        goalId: String,
        objective: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<GoalReminderWorker>()
                .setInputData(
                    workDataOf(
                        GoalReminderPayload.KEY_GOAL_ID to goalId,
                        GoalReminderPayload.KEY_OBJECTIVE to objective,
                    ),
                ).setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(workName: String) {
        workManager.cancelUniqueWork(workName)
    }
}
