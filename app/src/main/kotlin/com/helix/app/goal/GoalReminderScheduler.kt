package com.helix.app.goal

import android.app.NotificationManager
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
 * GoalReminderReconciler consumes durable checkpoint/state facts after user updates,
 * Turn settlement and application recovery. These queue operations never start a Goal run.
 *
 * The scheduling decision (work name, delay, payload) is a pure composition over
 * [ReminderPlan] and [GoalReminderPayload.uniqueWorkName], handed to a [ReminderEnqueuer]
 * seam so it is unit-testable on the JVM; the WorkManager REPLACE effect itself is
 * device-verified by GoalReminderTest.
 */
class GoalReminderScheduler(
    private val enqueuer: ReminderEnqueuer,
    private val cancelPosted: (String) -> Unit = {},
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
            checkpointEpochMillis = requireNotNull(checkpoint).atEpochMillis,
        )
    }

    fun wasDelivered(
        goalId: String,
        checkpointEpochMillis: Long,
    ): Boolean = enqueuer.wasDelivered(goalId, checkpointEpochMillis)

    /** Cancels the pending reminder for [goalId] (goal completed/failed/cancelled/input-required). */
    fun cancelReminder(goalId: String) {
        enqueuer.cancel(GoalReminderPayload.uniqueWorkName(goalId))
        cancelPosted(goalId)
    }

    companion object {
        fun create(context: Context): GoalReminderScheduler =
            GoalReminderScheduler(
                WorkManagerReminderEnqueuer(WorkManager.getInstance(context)) { goalId ->
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(goalId, GoalReminderWorker.notificationIdFor(goalId))
                    // Retire a pre-tag notification left by an older build as well.
                    manager.cancel(GoalReminderWorker.notificationIdFor(goalId))
                },
            )
    }
}

/**
 * Minimal WorkManager queue seam: keeps the scheduling decision (unique work name, delay,
 * payload) testable on the JVM. The production implementation is a unique-work enqueue with
 * [ExistingWorkPolicy.REPLACE] — one reminder per goal, reschedule replaces instead of
 * stacking (HXA-013 invariant).
 */
interface ReminderEnqueuer {
    fun wasDelivered(
        goalId: String,
        checkpointEpochMillis: Long,
    ): Boolean = false

    fun enqueueOrReplace(
        workName: String,
        delayMillis: Long,
        goalId: String,
        objective: String,
        checkpointEpochMillis: Long,
    )

    fun cancel(workName: String)
}

/** Keeps matching checkpoint work; a changed checkpoint replaces the prior unique work. */
class WorkManagerReminderEnqueuer(
    private val workManager: WorkManager,
    private val cancelPosted: (String) -> Unit = {},
) : ReminderEnqueuer {
    override fun enqueueOrReplace(
        workName: String,
        delayMillis: Long,
        goalId: String,
        objective: String,
        checkpointEpochMillis: Long,
    ) {
        val request =
            OneTimeWorkRequestBuilder<GoalReminderWorker>()
                .setInputData(
                    workDataOf(
                        GoalReminderPayload.KEY_GOAL_ID to goalId,
                        GoalReminderPayload.KEY_OBJECTIVE to objective,
                    ),
                ).addTag(checkpointTag(checkpointEpochMillis))
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
        GoalReminderPublication.serialized {
            val existing = GoalReminderPublication.await(workManager.getWorkInfosForUniqueWork(workName))
            if (existing.any {
                    checkpointTag(checkpointEpochMillis) in it.tags &&
                        (!it.state.isFinished || it.state == androidx.work.WorkInfo.State.SUCCEEDED)
                }
            ) {
                return@serialized
            }
            GoalReminderPublication.await(
                workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).result,
            )
            cancelPosted(goalId)
        }
    }

    override fun wasDelivered(
        goalId: String,
        checkpointEpochMillis: Long,
    ): Boolean =
        GoalReminderPublication.serialized {
            GoalReminderPublication
                .await(
                    workManager.getWorkInfosForUniqueWork(GoalReminderPayload.uniqueWorkName(goalId)),
                ).any {
                    checkpointTag(checkpointEpochMillis) in it.tags &&
                        it.state == androidx.work.WorkInfo.State.SUCCEEDED
                }
        }

    private fun checkpointTag(epochMillis: Long): String = "goal-checkpoint:$epochMillis"

    override fun cancel(workName: String) {
        GoalReminderPublication.serialized {
            GoalReminderPublication.await(workManager.cancelUniqueWork(workName).result)
            cancelPosted(workName.removePrefix(GoalReminderPayload.UNIQUE_WORK_PREFIX))
        }
    }
}
