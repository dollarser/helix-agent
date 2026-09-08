package com.helix.app.goal

import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Serializes local notification publication with durable WorkManager replacement/cancellation. */
internal object GoalReminderPublication {
    private val lock = Any()

    fun <T> serialized(action: () -> T): T = synchronized(lock, action)

    fun publishIfRunning(
        workManager: WorkManager,
        workId: UUID,
        publish: () -> Unit,
    ): Boolean =
        serialized {
            val running = await(workManager.getWorkInfoById(workId))?.state == WorkInfo.State.RUNNING
            if (running) publish()
            running
        }

    fun <T> await(operation: Future<T>): T =
        try {
            operation.get(10, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw IllegalStateException("Reminder work operation failed", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException("Reminder work operation timed out", error)
        }
}
