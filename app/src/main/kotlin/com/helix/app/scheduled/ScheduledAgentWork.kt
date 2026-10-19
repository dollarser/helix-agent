package com.helix.app.scheduled

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.helix.app.HelixApplication
import com.helix.core.storage.scheduled.ScheduledAgentRule
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** WorkManager is a delayed wake projection, never execution authority or an exact alarm. */
class AndroidScheduledAgentWake(
    private val context: Context,
) : ScheduledAgentWake {
    override suspend fun schedule(
        rule: ScheduledAgentRule,
        afterCurrent: Boolean,
    ) {
        val due = requireNotNull(rule.nextAt)
        val request =
            OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
                .setInitialDelay(maxOf(0, due - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("rule" to rule.id, "revision" to rule.revision, "due" to due))
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                key(rule),
                if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            ).result
            .get(10, TimeUnit.SECONDS)
    }

    override suspend fun cancelFuture(rule: ScheduledAgentRule) {
        WorkManager
            .getInstance(context)
            .cancelUniqueWork(key(rule))
            .result
            .get(10, TimeUnit.SECONDS)
    }

    private fun key(rule: ScheduledAgentRule) =
        "scheduled-agent:${rule.id}:${rule.revision}:${requireNotNull(rule.nextAt)}"
}

class ScheduledAgentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught") // Failure is terminal; never silently replay an attempt.
    override suspend fun doWork(): Result {
        val id = inputData.getString("rule").orEmpty()
        val revision = inputData.getLong("revision", -1)
        val due = inputData.getLong("due", -1)
        if (id.isBlank() || revision < 1 || due < 0) return Result.failure()
        return try {
            val app = applicationContext as HelixApplication
            val container = app.awaitAppContainer()
            app.runtimeRecoveryReady.await()
            val service = requireNotNull(container.scheduledAgents)
            service.fire(id, revision, due)
            if (service.occurrenceOutcome(id, revision, due) in setOf("FAILED", "UNKNOWN", "INTERRUPTED")) {
                Result.failure()
            } else {
                Result.success()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.e("HelixScheduled", "Scheduled wake failed; no automatic attempt replay", error)
            Result.failure()
        }
    }
}

class ScheduledAgentRecoveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught") // Recovery failure is not a successful audit.
    override suspend fun doWork(): Result =
        try {
            val app = applicationContext as HelixApplication
            val container = app.awaitAppContainer()
            app.runtimeRecoveryReady.await()
            requireNotNull(container.scheduledAgents).reconcile()
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.e("HelixScheduled", "Scheduled recovery failed", error)
            Result.failure()
        }
}

object ScheduledAgentRecovery {
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "scheduled-agent-reconcile",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScheduledAgentRecoveryWorker>().build(),
        )
    }

    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "scheduled-agent-audit",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScheduledAgentRecoveryWorker>(12, TimeUnit.HOURS).build(),
        )
    }
}
