package com.helix.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.helix.app.goal.GoalReminderPayload
import com.helix.app.goal.GoalReminderScheduler
import com.helix.app.goal.GoalReminderWorker
import com.helix.core.agent.Checkpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device acceptance for the deferrable Goal checkpoint reminder (verification matrix HXA-013,
 * "提醒/恢复"): the WorkManager path must post the reminder notification, must never touch
 * model or tool code, and reschedules must replace (not stack) the pending reminder.
 *
 * Process-death recovery of a RUNNING goal (RUNNING -> PAUSED park, checkpoint reminder
 * survives as a wake source) is asserted at reducer level in `:core:agent:test`; the full
 * process-recovery instrumentation fixture belongs to HXA-015.
 */
@RunWith(AndroidJUnit4::class)
class GoalReminderTest {
    @Test
    fun deferrableReminderPostsNotificationWithoutModelOrToolWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val scheduler = GoalReminderScheduler.create(context)
        val now = System.currentTimeMillis()
        // WorkManager rounds small initial delays up (to ~10s), so allow a generous window.
        val objective = "Verify the reminder path"
        scheduler.scheduleReminder("goal-reminder-test", objective, Checkpoint(now + 5_000L), now)
        waitUntilWorkerProcessed(objective)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(
            "reminder notification expected after the worker ran",
            manager.activeNotifications.any { it.id == GoalReminderWorker.NOTIFICATION_ID },
        )
        assertEquals(
            "worker must never invoke a model or tool (architecture doc 5.1)",
            0,
            GoalReminderWorker.modelOrToolInvocations.get(),
        )
        scheduler.cancelReminder("goal-reminder-test")
    }

    @Test
    fun rescheduleReplacesPreviousWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val scheduler = GoalReminderScheduler.create(context)
        val now = System.currentTimeMillis()
        scheduler.scheduleReminder("goal-replace-test", "First objective", Checkpoint(now + 3_600_000L), now)
        val secondObjective = "Second objective"
        scheduler.scheduleReminder("goal-replace-test", secondObjective, Checkpoint(now + 5_000L), now)
        val workName = GoalReminderPayload.uniqueWorkName("goal-replace-test")
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
        assertEquals("reschedule must replace, not stack, the reminder", 1, infos.size)
        // The short-delay (replaced-in) work is the one that executes, proving the first
        // (1-hour) work no longer exists.
        waitUntilWorkerProcessed(secondObjective)
        scheduler.cancelReminder("goal-replace-test")
        // WorkInfo records persist for terminal work; the guarantee is that nothing pending
        // (ENQUEUED/RUNNING) remains for this goal.
        val afterCancel = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()
        assertTrue(
            "cancelling must leave no pending reminder",
            afterCancel.all {
                it.state in
                    setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, WorkInfo.State.FAILED)
            },
        )
    }

    /** Polls the worker evidence slot until it reports [expectedObjective] (up to 120s). */
    private fun waitUntilWorkerProcessed(expectedObjective: String) {
        val deadline = System.currentTimeMillis() + 120_000L
        while (GoalReminderWorker.lastProcessedObjective.get() != expectedObjective) {
            assertTrue(
                "reminder worker did not process '$expectedObjective' within 120s",
                System.currentTimeMillis() < deadline,
            )
            Thread.sleep(1_000L)
        }
    }

    /** Grants POST_NOTIFICATIONS (API 33+) for the app under test via the test's UiAutomation. */
    private fun grantNotificationPermission(context: Context) {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(
            "POST_NOTIFICATIONS not granted for tests",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
}
