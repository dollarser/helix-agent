package com.helix.app

import android.content.Context
import android.os.Bundle
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
import com.helix.core.agent.Checkpoint
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.GoalBudgets
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host kills inside an open deletion transaction and after its successful commit. */
@RunWith(AndroidJUnit4::class)
class GoalDeletionProcessKillDeviceTest {
    @Test
    fun interruptedDeletionPreservesOwnerAndCommittedDeletionDoesNotResurrect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phase = InstrumentationRegistry.getArguments().getString("goal.delete.kill.phase") ?: "control"
        require(phase in setOf("control", "prepare", "recover-commit", "recover-final"))
        val content = File(context.cacheDir, "goal-delete-kill")
        val marker = File(content, "goal-id.txt")
        if (phase in setOf("control", "prepare")) {
            check(!marker.exists()) { "Previous deletion kill fixture needs recovery before another run" }
            context.deleteDatabase(DATABASE)
            content.mkdirs()
        }
        val storage = HelixStorage.open(context, DATABASE, content)
        val scheduler = GoalReminderScheduler.create(context)
        try {
            when (phase) {
                "prepare" -> {
                    val id = prepare(storage, scheduler)
                    marker.writeText(id)
                    GoalDeletionCoordinator(storage) {
                        scheduler.cancelReminder(it)
                        assertTrue(work(context, it).all { info -> info.state.isFinished })
                        awaitHostKill("before-commit")
                    }.delete(id)
                }

                "recover-commit" -> {
                    recoverAndDelete(context, storage, scheduler, marker.readText())
                    awaitHostKill("after-commit")
                }

                "recover-final" -> {
                    verifyDeleted(context, storage, scheduler, marker.readText())
                }

                else -> {
                    val id = prepare(storage, scheduler)
                    assertThrows(InterruptedDeletion::class.java) {
                        GoalDeletionCoordinator(storage) {
                            scheduler.cancelReminder(it)
                            throw InterruptedDeletion()
                        }.delete(id)
                    }
                    recoverAndDelete(context, storage, scheduler, id)
                    verifyDeleted(context, storage, scheduler, id)
                }
            }
        } finally {
            storage.close()
        }
        if (phase in setOf("control", "recover-final")) {
            context.deleteDatabase(DATABASE)
            content.deleteRecursively()
        }
    }

    private fun prepare(
        storage: HelixStorage,
        scheduler: GoalReminderScheduler,
    ): String {
        val clock = SystemClock()
        val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
        storage.sessions.create("session", "Deletion kill fixture", null, null, clock.now().toEpochMilli())
        val id =
            coordinator.create(
                "Deletion kill fixture",
                listOf("Checked"),
                GoalBudgets(2, 4, 1000, 60000, 10000, 0),
            )
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
        started.coordinator.beginModelStream()
        started.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
        assertTrue(coordinator.setCheckpoint(id, Checkpoint(clock.now().toEpochMilli() + 3_600_000)))
        GoalReminderReconciler(storage, scheduler, clock).reconcile(id)
        return id
    }

    private fun recoverAndDelete(
        context: Context,
        storage: HelixStorage,
        scheduler: GoalReminderScheduler,
        id: String,
    ) {
        val goal = storage.goals.resolve(id)
        assertEquals("PAUSED", goal.state)
        assertTrue(requireNotNull(goal.nextCheckpoint) > System.currentTimeMillis())
        assertEquals(1, storage.goalRuns.listByGoal(id).size)
        assertTrue(storage.goalRuns.listByGoal(id).all { it.endedAt != null })
        assertTrue(work(context, id).all { it.state.isFinished })
        val reconciler = GoalReminderReconciler(storage, scheduler, SystemClock())
        reconciler.reconcile(id)
        val resumed = work(context, id).single { !it.state.isFinished }
        reconciler.reconcile(id)
        assertEquals(resumed.id, work(context, id).single { !it.state.isFinished }.id)
        assertEquals(goal, storage.goals.resolve(id))
        assertEquals(1, storage.turns.listBySession("session").size)
        GoalDeletionCoordinator(storage, scheduler::cancelReminder).delete(id)
        verifyDeleted(context, storage, scheduler, id)
    }

    private fun verifyDeleted(
        context: Context,
        storage: HelixStorage,
        scheduler: GoalReminderScheduler,
        id: String,
    ) {
        val reconciler = GoalReminderReconciler(storage, scheduler, SystemClock())
        repeat(2) { reconciler.reconcile(id) }
        assertNull(storage.goals.find(id))
        assertTrue(storage.goalRuns.listByGoal(id).isEmpty())
        assertTrue(work(context, id).all { it.state.isFinished })
        assertEquals(1, storage.turns.listBySession("session").size)
    }

    private fun work(
        context: Context,
        id: String,
    ) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(GoalReminderPayload.uniqueWorkName(id)).get()

    private fun awaitHostKill(boundary: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "GOAL_DELETE_KILL_READY boundary=$boundary pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30_000)
        error("Host did not kill the prepared deletion process")
    }

    private class InterruptedDeletion : IllegalStateException()

    companion object {
        private const val DATABASE = "goal-delete-kill.db"
    }
}
