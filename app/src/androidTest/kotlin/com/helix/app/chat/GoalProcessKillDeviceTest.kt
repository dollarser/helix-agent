package com.helix.app.chat

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.recovery.GoalUsageReservations
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** Host-driven SIGKILL phases; the default phase runs the same assertions as an in-process control. */
@RunWith(AndroidJUnit4::class)
class GoalProcessKillDeviceTest {
    @Test
    fun repeatedProcessDeathDoesNotReplenishGoalBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phase = InstrumentationRegistry.getArguments().getString("goal.kill.phase") ?: "control"
        require(phase in setOf("control", "prepare", "recover-prepare", "recover-final"))
        val content = File(context.cacheDir, "goal-process-kill")
        val marker = File(content, "goal-id.txt")
        if (phase == "control" || phase == "prepare") {
            context.deleteDatabase(DATABASE)
            content.deleteRecursively()
            content.mkdirs()
        }
        val storage = HelixStorage.open(context, DATABASE, content)
        try {
            when (phase) {
                "prepare" -> {
                    marker.writeText(prepareFirst(storage))
                    awaitHostKill()
                }

                "recover-prepare" -> {
                    val goalId = marker.readText()
                    recoverAndCheck(storage, goalId, 1, 1)
                    reserveRun(storage, goalId, "second")
                    awaitHostKill()
                }

                "recover-final" -> {
                    recoverAndCheck(storage, marker.readText(), 2, 999_999_999)
                }

                else -> {
                    val goalId = prepareFirst(storage)
                    recoverAndCheck(storage, goalId, 1, 1)
                    reserveRun(storage, goalId, "second")
                    recoverAndCheck(storage, goalId, 2, 999_999_999)
                }
            }
        } finally {
            storage.close()
        }
        if (phase == "control" || phase == "recover-final") {
            context.deleteDatabase(DATABASE)
            content.deleteRecursively()
        }
    }

    private fun prepareFirst(storage: HelixStorage): String {
        storage.sessions.create("session", "Kill boundary fixture", null, null, 1_000)
        val goalId =
            coordinator(storage).create(
                "Bound interrupted execution",
                listOf("Verified result"),
                GoalBudgets(5, 5, 1_000, 10_000, 10_000, 0),
            )
        reserveRun(storage, goalId, "first")
        return goalId
    }

    private fun reserveRun(
        storage: HelixStorage,
        goalId: String,
        turnId: String,
    ) {
        val started = requireNotNull(coordinator(storage).start(request(goalId, turnId)))
        assertTrue(GoalTimeBudget(storage, clock(2_000), started.runId).start())
        assertTrue(
            GoalUsageReservations(storage).reserve(
                GoalUsageReservations.Request(
                    "model-$turnId",
                    started.runId,
                    GoalUsageReservations.Kind.MODEL,
                    tokens = 100,
                ),
            ),
        )
        assertEquals(2, storage.goalUsageReservations.pendingForRun(started.runId).size)
    }

    private fun recoverAndCheck(
        storage: HelixStorage,
        goalId: String,
        runs: Int,
        wallTime: Long,
    ) {
        val report = RecoveryCoordinatorApp(storage, clock(wallTime)).recover()
        assertEquals(1, report.closedRuns.size)
        val goal = storage.goals.resolve(goalId)
        assertEquals("PAUSED", goal.state)
        assertEquals(runs * 5_000L, goal.runTimeMillis)
        assertEquals(runs * 100L, goal.totalTokens)
        assertEquals(runs, goal.modelCalls)
        assertEquals(0L, goal.currentWakeMillis)
        assertEquals(runs, storage.turns.listBySession("session").size)
        storage.goalRuns.listByGoal(goalId).forEach {
            assertNotNull(it.endedAt)
            assertTrue(storage.goalUsageReservations.pendingForRun(it.id).isEmpty())
        }
        assertTrue(RecoveryCoordinatorApp(storage, clock(wallTime)).recover().closedRuns.isEmpty())
        assertEquals(goal, storage.goals.resolve(goalId))
        if (runs == 2) {
            assertNull(coordinator(storage).start(request(goalId, "forbidden-third")))
            assertEquals(2, storage.turns.listBySession("session").size)
        }
    }

    private fun awaitHostKill() {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "GOAL_KILL_READY pid=${android.os.Process.myPid()}\n") },
        )
        Thread.sleep(30_000)
        error("Host did not kill the prepared process")
    }

    private fun coordinator(storage: HelixStorage) =
        GoalRunCoordinator(storage, clock(2_000)) {
            UUID.randomUUID().toString()
        }

    private fun request(
        goalId: String,
        turnId: String,
    ) = GoalTurnStart(
        goalId,
        GoalWakeReason.USER_OPEN,
        TurnStartSpec("session", turnId, "message-$turnId", "snapshot", "run"),
        TurnBudgets(5, 10, 800, 800, 5_000),
    )

    private fun clock(millis: Long) =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(millis)
        }

    companion object {
        private const val DATABASE = "goal-process-kill.db"
    }
}
