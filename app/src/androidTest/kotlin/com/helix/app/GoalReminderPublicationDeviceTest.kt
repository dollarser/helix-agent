package com.helix.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.helix.app.goal.GoalReminderPayload
import com.helix.app.goal.GoalReminderPublication
import com.helix.app.goal.GoalReminderScheduler
import com.helix.app.goal.GoalReminderWorker
import com.helix.app.goal.ensureReminderChannel
import com.helix.core.agent.Checkpoint
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real WorkManager state and NotificationManager effects with a deliberately late publication actor. */
@RunWith(AndroidJUnit4::class)
class GoalReminderPublicationDeviceTest {
    @Test
    fun cancelledWorkCannotPublishFromALateActor() =
        withFixture { fixture ->
            fixture.scheduler.cancelReminder(fixture.goalId)
            assertFalse(fixture.publish())
            fixture.awaitNotification(false)
        }

    @Test
    fun replacementRevokesOldWorkAndRemovesItsNotification() =
        withFixture { fixture ->
            assertTrue(fixture.publish())
            fixture.awaitNotification(true)
            val now = System.currentTimeMillis()
            fixture.scheduler.scheduleReminder(fixture.goalId, "Replacement", Checkpoint(now + 3_600_000), now)
            assertFalse(fixture.publish())
            fixture.awaitNotification(false)
        }

    @Test
    fun cancellationWaitsForInProgressPublicationThenRemovesIt() =
        withFixture { fixture ->
            val publishing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cancelling = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val post =
                    executor.submit<Boolean> {
                        fixture.publish {
                            publishing.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(publishing.await(10, TimeUnit.SECONDS))
                val cancel =
                    executor.submit {
                        cancelling.countDown()
                        fixture.scheduler.cancelReminder(fixture.goalId)
                    }
                assertTrue(cancelling.await(10, TimeUnit.SECONDS))
                assertFalse(cancel.isDone)
                release.countDown()
                assertTrue(post.get(10, TimeUnit.SECONDS))
                cancel.get(10, TimeUnit.SECONDS)
                fixture.awaitNotification(false)
                assertFalse(fixture.publish())
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            fixture.start()
            block(fixture)
        } finally {
            fixture.scheduler.cancelReminder(fixture.goalId)
        }
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManager = WorkManager.getInstance(context)
        val scheduler = GoalReminderScheduler.create(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val goalId = "publication-${UUID.randomUUID()}"
        val request = OneTimeWorkRequestBuilder<ReminderPublicationWaitingWorker>().build()

        fun start() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                InstrumentationRegistry
                    .getInstrumentation()
                    .uiAutomation
                    .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            ensureReminderChannel(context)
            GoalReminderPublication.await(
                workManager
                    .enqueueUniqueWork(
                        GoalReminderPayload.uniqueWorkName(goalId),
                        ExistingWorkPolicy.REPLACE,
                        request,
                    ).result,
            )
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (GoalReminderPublication.await(workManager.getWorkInfoById(request.id))?.state !=
                WorkInfo.State.RUNNING
            ) {
                assertTrue("Worker did not start", android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(50)
            }
        }

        fun publish(beforePost: () -> Unit = {}): Boolean =
            GoalReminderPublication.publishIfRunning(workManager, request.id) {
                beforePost()
                manager.notify(
                    goalId,
                    GoalReminderWorker.notificationIdFor(goalId),
                    Notification
                        .Builder(
                            context,
                            GoalReminderPayload.CHANNEL_ID,
                        ).setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Publication fixture")
                        .build(),
                )
            }

        fun awaitNotification(expected: Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (manager.activeNotifications.any { it.tag == goalId } != expected) {
                assertTrue("Unexpected notification state", android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(50)
            }
        }
    }
}

/** Holds a real RUNNING WorkSpec; the fixture invokes the same publication gate as the real reminder. */
class ReminderPublicationWaitingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = awaitCancellation()
}
