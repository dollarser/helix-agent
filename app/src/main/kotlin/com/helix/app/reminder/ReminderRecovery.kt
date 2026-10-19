package com.helix.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.helix.app.HelixApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

class ReminderReceiver : BroadcastReceiver() {
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Reject malformed broadcasts before taking async ownership.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderPlatform.ACTION_FIRE) {
            val uri = intent.data ?: return
            val parts = uri.pathSegments
            if (uri.scheme != "helix" || uri.authority != "reminder-alarm" || parts.size != 1) return
            val revision = intent.getLongExtra("revision", -1)
            val due = intent.getLongExtra("due", -1)
            if (revision < 1 || due < 0) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(8000) {
                        (context.applicationContext as HelixApplication).reminders.fire(parts[0], revision, due)
                    }
                } catch (error: Exception) {
                    android.util.Log.e("HelixReminder", "Alarm processing interrupted; reconciliation required", error)
                } finally {
                    // A durable POSTING remains UNKNOWN on recovery if publication was interrupted.
                    pending.finish()
                    ReminderRecovery.enqueue(context)
                }
            }
        } else if (intent.action in RECOVERY_ACTIONS) {
            android.util.Log.i("HelixReminder", "Rescheduling after ${intent.action}")
            ReminderRecovery.enqueue(context)
            com.helix.app.scheduled.ScheduledAgentRecovery
                .enqueue(context)
        }
    }

    companion object {
        val RECOVERY_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                ALARM_PERMISSION_ACTION,
            )
        private const val ALARM_PERMISSION_ACTION = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

class ReminderRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as HelixApplication).reminders.reconcile()
        return Result.success()
    }
}

object ReminderRecovery {
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "local-reminder-reconcile",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderRecoveryWorker>().build(),
        )
    }

    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "local-reminder-audit",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderRecoveryWorker>(12, TimeUnit.HOURS).build(),
        )
    }
}
