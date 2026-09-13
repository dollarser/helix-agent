package com.helix.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.GoalRunCoordinator
import com.helix.app.chat.GoalTurnStart
import com.helix.app.goal.GoalDeletionCoordinator
import com.helix.app.goal.GoalReminderPayload
import com.helix.app.goal.GoalReminderReconciler
import com.helix.app.goal.GoalReminderScheduler
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.GoalBudgets
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalDeletionDeviceTest {
    private val clock = SystemClock()
    private val budgets = GoalBudgets(2, 4, 1000, 60000, 10000, 0)

    @Test
    fun productionDeletionRemovesQueuedAndPostedReminders() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val storage = app.appContainer.storage
        val scheduler = GoalReminderScheduler.create(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val ids = List(2) { coordinator(storage).create("Deletion fixture", listOf("Verified result"), budgets) }
        try {
            val now = clock.now().toEpochMilli()
            GoalReminderReconciler.serialized {
                ids.forEachIndexed { index, id ->
                    storage.goals.updateGoal(
                        storage.goals.resolve(id).copy(state = "PAUSED", nextCheckpoint = now + index * 3_600_000L),
                    )
                    GoalReminderReconciler(storage, scheduler, clock).reconcile(id)
                }
            }
            awaitNotification(manager, ids[0], true)
            ids.forEach { id ->
                assertEquals(
                    1,
                    app.appContainer.privacyDeletionService
                        .deleteGoal(id)
                        .deletedItems,
                )
                assertNull(storage.goals.find(id))
                awaitNotification(manager, id, false)
                assertTrue(
                    WorkManager
                        .getInstance(app)
                        .getWorkInfosForUniqueWork(
                            GoalReminderPayload.uniqueWorkName(id),
                        ).get()
                        .all { it.state.isFinished },
                )
                // A stale snapshot from before deletion must cancel safely, not abort recovery.
                GoalReminderReconciler(storage, scheduler, clock).reconcile(id)
            }
        } finally {
            ids.forEach { id ->
                scheduler.cancelReminder(id)
                if (storage.goals.find(id) != null) app.appContainer.privacyDeletionService.deleteGoal(id)
            }
        }
    }

    @Test
    fun runningGoalCannotBeDeletedOrLoseItsReminder() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Running fixture", listOf("Verified result"), budgets)
            val started =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            id,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "turn", "model", "snapshot", "run"),
                            TurnBudgets(2, 4, 500, 500, 10000),
                        ),
                    ),
                )
            val before = storage.goals.resolve(id)
            var cancellations = 0
            val deletion = GoalDeletionCoordinator(storage) { cancellations++ }
            assertThrows(IllegalStateException::class.java) { deletion.delete(id) }
            assertEquals(before, storage.goals.resolve(id))
            assertNull(storage.goalRuns.resolve(started.runId).endedAt)
            assertEquals(0, cancellations)
            storage.goals.updateGoal(before.copy(state = "PAUSED"))
            assertThrows(IllegalStateException::class.java) { deletion.delete(id) }
            assertEquals(0, cancellations)
            assertNull(storage.goalRuns.resolve(started.runId).endedAt)
            storage.goals.updateGoal(before)
            started.coordinator.beginModelStream()
            started.coordinator.terminalize(
                ModelStreamTerminal(com.helix.core.model.TurnState.COMPLETED, null),
            )
            deletion.delete(id)
            assertNull(storage.goals.find(id))
            assertTrue(storage.goalRuns.listByGoal(id).isEmpty())
            assertEquals(1, cancellations)
        }

    @Test
    fun reminderCancellationFailurePreservesGoal() =
        withStorage { storage ->
            val id = coordinator(storage).create("Failure fixture", listOf("Verified result"), budgets)
            val before = storage.goals.resolve(id)
            val deletion = GoalDeletionCoordinator(storage) { error("fixture cancellation failure") }
            assertThrows(IllegalStateException::class.java) { deletion.delete(id) }
            assertEquals(before, storage.goals.resolve(id))
            assertNotNull(storage.goals.resolveEntity(id))
        }

    private fun awaitNotification(
        manager: NotificationManager,
        id: String,
        expected: Boolean,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (manager.activeNotifications.any { it.tag == id } != expected) {
            assertTrue("Notification did not settle", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(50)
        }
    }

    private fun coordinator(storage: HelixStorage) = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-delete-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create("session", "Deletion fixture", null, null, clock.now().toEpochMilli())
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
