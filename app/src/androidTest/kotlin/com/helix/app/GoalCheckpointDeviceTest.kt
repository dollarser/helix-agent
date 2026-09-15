package com.helix.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.GoalRunCoordinator
import com.helix.app.chat.GoalTurnStart
import com.helix.app.goal.GoalReminderPayload
import com.helix.app.goal.GoalReminderReconciler
import com.helix.app.goal.GoalReminderScheduler
import com.helix.core.agent.Checkpoint
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.GoalBudgets
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalCheckpointDeviceTest {
    @Test
    fun recoveryPreservesPendingWorkAndConsumesSuccessfulCheckpointOnce() =
        withFixture { fixture ->
            val future = fixture.clock.now().toEpochMilli() + 3_600_000
            fixture.schedule(future)
            val first = fixture.work().single().id
            fixture.reconcile()
            assertEquals(first, fixture.work().single().id)
            fixture.schedule(fixture.clock.now().toEpochMilli())
            assertNotEquals(first, fixture.work().single().id)
            fixture.awaitSuccess()
            val before = fixture.storage.goals.resolve(fixture.goalId)
            val runs = fixture.storage.goalRuns.listByGoal(fixture.goalId)
            fixture.reconcile()
            assertEquals(before.copy(nextCheckpoint = null), fixture.storage.goals.resolve(fixture.goalId))
            val completed = fixture.work().map { it.id }
            fixture.reconcile()
            assertNull(
                fixture.storage.goals
                    .resolve(fixture.goalId)
                    .nextCheckpoint,
            )
            assertEquals(completed, fixture.work().map { it.id })
            assertEquals(runs, fixture.storage.goalRuns.listByGoal(fixture.goalId))
        }

    @Test
    fun oldSuccessCannotConsumeANewerCheckpoint() =
        withFixture { fixture ->
            fixture.schedule(fixture.clock.now().toEpochMilli())
            fixture.awaitSuccess()
            val oldId = fixture.work().single().id
            val future = fixture.clock.now().toEpochMilli() + 3_600_000
            fixture.schedule(future)
            assertEquals(
                future,
                fixture.storage.goals
                    .resolve(fixture.goalId)
                    .nextCheckpoint,
            )
            assertNotEquals(oldId, fixture.work().single().id)
            fixture.reconcile()
            assertEquals(
                future,
                fixture.storage.goals
                    .resolve(fixture.goalId)
                    .nextCheckpoint,
            )
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.scheduler.cancelReminder(fixture.goalId)
            fixture.storage.close()
            fixture.context.deleteDatabase(fixture.name)
            fixture.content.deleteRecursively()
        }
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "checkpoint-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        val clock = SystemClock()
        val scheduler = GoalReminderScheduler.create(context)
        val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
        val goalId: String

        init {
            storage.sessions.create("session", "Checkpoint fixture", null, null, clock.now().toEpochMilli())
            goalId =
                coordinator.create(
                    "Checkpoint fixture",
                    listOf("Verified result"),
                    GoalBudgets(2, 4, 1000, 60000, 10000, 0),
                )
            val started =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goalId,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "turn", "model", "snapshot", "run"),
                            TurnBudgets(2, 4, 500, 500, 10000),
                        ),
                    ),
                )
            started.coordinator.beginModelStream()
            started.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        }

        fun schedule(epoch: Long) {
            assertTrue(coordinator.setCheckpoint(goalId, Checkpoint(epoch)))
            reconcile()
        }

        fun reconcile() = GoalReminderReconciler(storage, scheduler, clock).reconcile(goalId)

        fun work(): List<WorkInfo> =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(GoalReminderPayload.uniqueWorkName(goalId))
                .get()

        fun awaitSuccess() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (work().none { it.state == WorkInfo.State.SUCCEEDED }) {
                assertTrue("Reminder did not complete", android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(50)
            }
        }
    }
}
