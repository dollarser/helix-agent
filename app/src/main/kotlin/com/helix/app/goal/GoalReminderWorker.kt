package com.helix.app.goal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.helix.app.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Posts the deferrable Goal checkpoint reminder as a local notification.
 *
 * Architecture rule (doc 02 section 5.1): WorkManager is used for Goal reminders and may
 * never start model or tool work in the background. This worker therefore does exactly one
 * thing: post a notification that invites the user to continue the goal
 * ([GoalWakeReason.NOTIFICATION_ACTION] in the Goal reducer).
 */
class GoalReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val goalId = inputData.getString(GoalReminderPayload.KEY_GOAL_ID) ?: return Result.failure()
        val objective = inputData.getString(GoalReminderPayload.KEY_OBJECTIVE).orEmpty()
        lastProcessedObjective.set(objective)
        ensureReminderChannel(applicationContext)
        val notification =
            Notification
                .Builder(applicationContext, GoalReminderPayload.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Helix goal checkpoint")
                .setContentText(GoalReminderPayload.text(objective))
                .setContentIntent(contentIntent(applicationContext, goalId))
                .setAutoCancel(true)
                .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    private fun contentIntent(
        context: Context,
        goalId: String,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(GoalReminderPayload.KEY_GOAL_ID, goalId)
            }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 4201

        /**
         * Invariant counter: this worker must never invoke a model or tool. The instrumented
         * test asserts it stays 0, turning the architecture rule into executable evidence.
         */
        val modelOrToolInvocations = AtomicInteger(0)

        /**
         * Executable evidence for the device test: the objective of the most recently executed
         * reminder. `WorkInfo` does not expose input data, so the test reads it back from here.
         */
        val lastProcessedObjective =
            java.util.concurrent.atomic
                .AtomicReference<String?>(null)
    }
}

/** Creates the reminder channel if missing (minSdk 29 always has channels). */
internal fun ensureReminderChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(GoalReminderPayload.CHANNEL_ID) == null) {
        val channel =
            NotificationChannel(
                GoalReminderPayload.CHANNEL_ID,
                GoalReminderPayload.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = "Deferrable checkpoint reminders for persistent goals"
        manager.createNotificationChannel(channel)
    }
}
