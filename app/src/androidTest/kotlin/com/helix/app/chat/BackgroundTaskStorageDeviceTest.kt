package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class BackgroundTaskStorageDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(2000)
        }

    @Test fun collectionIsTerminalOnlyIdempotentAndSurvivesReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task-results-${UUID.randomUUID()}.db"
        try {
            HelixStorage.open(context, name, java.io.File(context.cacheDir, name)).withClose { storage ->
                storage.sessions.create("s", "Fixture", null, null, 1000)
                val turn =
                    TurnCoordinator.start(
                        storage,
                        clock,
                        ::id,
                        TurnStartSpec("s", "t", "m", "snapshot", "input"),
                    )
                assertFalse(storage.turns.collectResult("t", 2001))
                turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
                assertTrue(storage.turns.collectResult("t", 2001))
                assertFalse(storage.turns.collectResult("t", 2002))
            }
            HelixStorage.open(context, name, java.io.File(context.cacheDir, name)).withClose { storage ->
                assertEquals(2001L, storage.turns.resolve("t").resultCollectedAt)
                assertTrue(BackgroundTaskQuery(storage).read().single().collected)
                assertEquals("CANCELLED", storage.turns.resolve("t").state)
                assertEquals(1, storage.modelCalls.listByTurn("t").size)
            }
        } finally {
            context.deleteDatabase(name)
            java.io.File(context.cacheDir, name).deleteRecursively()
        }
    }

    @Test fun userPauseKeepsGoalResumableAndNeverReplaysTheCancelledTurn() =
        fixture { storage, coordinator, goal ->
            val first = requireNotNull(coordinator.start(request(goal, "first")))
            assertTrue(storage.turns.requestPause("first", 2001))
            first.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            assertEquals("USER_PAUSED", storage.goalRuns.resolve(first.runId).outcome)
            assertFalse(storage.turns.requestPause("first", 2002))
            assertNotNull(coordinator.start(request(goal, "second")))
            assertEquals("CANCELLED", storage.turns.resolve("first").state)
            assertEquals(2, storage.goalRuns.listByGoal(goal).size)
        }

    @Test fun missingBindingsNeverBlockOrdinaryProgress() =
        fixture { storage, coordinator, goal ->
            val first = requireNotNull(coordinator.start(request(goal, "first")))
            first.coordinator.beginModelStream()
            first.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            RecoveryCoordinatorApp(storage, clock).recover()
            assertNotNull(coordinator.start(request(goal, "second")))
        }

    @Test fun contextBlockerCannotBeAcknowledgedWhileCapacityStillFails() =
        fixture { storage, coordinator, goal ->
            val first = requireNotNull(coordinator.start(request(goal, "first")))
            first.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT"))
            val resolution = GoalBlockerResolution(storage, clock, ::id)
            assertEquals("BLOCKED", storage.goals.resolve(goal).state)
            assertFalse(resolution.resolve(goal, "other-session", true))
            assertFalse(resolution.resolve(goal, "s", false))
            assertTrue(resolution.resolve(goal, "s", true))
            assertEquals("PAUSED", storage.goals.resolve(goal).state)
            assertEquals(1, storage.goalRuns.listByGoal(goal).size)
        }

    @Test fun processRecoveryKeepsUnknownEffectsBlockedEvenAfterPauseRequest() =
        fixture { storage, coordinator, goal ->
            requireNotNull(coordinator.start(request(goal, "first")))
            storage.toolCalls.append("unknown", "first", "unknown", "files.write", "1", "{}", "NEEDS_REVIEW")
            assertTrue(storage.turns.requestPause("first", 2001))
            RecoveryCoordinatorApp(storage, clock).recover()
            assertEquals("BLOCKED", storage.goals.resolve(goal).state)
            assertFalse(GoalBlockerResolution(storage, clock, ::id).resolve(goal, "s", true))
            assertNull(coordinator.start(request(goal, "second")))
            assertEquals("NEEDS_REVIEW", storage.toolCalls.resolve("unknown").state)
        }

    @Test fun recoveryChargesReservedBudgetAndKeepsExhaustionBlocked() =
        fixture { storage, coordinator, goal ->
            val first = requireNotNull(coordinator.start(request(goal, "first")))
            val journal =
                com.helix.app.recovery
                    .GoalUsageReservations(storage)
            assertTrue(
                journal.reserve(
                    com.helix.app.recovery.GoalUsageReservations.Request(
                        "reservation",
                        first.runId,
                        com.helix.app.recovery.GoalUsageReservations.Kind.MODEL,
                        10000,
                        0,
                    ),
                ),
            )
            RecoveryCoordinatorApp(storage, clock).recover()
            assertEquals("BLOCKED", storage.goals.resolve(goal).state)
            assertEquals(10000L, storage.goals.resolve(goal).totalTokens)
            assertFalse(GoalBlockerResolution(storage, clock, ::id).resolve(goal, "s", true))
            assertNull(coordinator.start(request(goal, "second")))
            RecoveryCoordinatorApp(storage, clock).recover()
            assertEquals(10000L, storage.goals.resolve(goal).totalTokens)
        }

    @Test fun uncollectedResultsRemainVisibleBeyondRecentHistoryLimit() =
        fixture { storage, _, _ ->
            repeat(205) { index ->
                val turn =
                    TurnCoordinator.start(
                        storage,
                        clock,
                        ::id,
                        TurnStartSpec("s", "t-$index", "m-$index", "snapshot", "input"),
                    )
                turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            }
            assertEquals(205, BackgroundTaskQuery(storage).read().size)
            assertTrue(BackgroundTaskQuery(storage).read().any { it.id == "t-0" && it.canCollect })
        }

    @Test fun taskAndGoalSnapshotsStayConsistentDuringGoalDeletion() =
        fixture { storage, coordinator, _ ->
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val start = java.util.concurrent.CountDownLatch(1)
            val reader =
                Thread {
                    try {
                        start.await()
                        repeat(100) {
                            BackgroundTaskQuery(storage).read()
                            GoalSummaryQuery(storage).forSession("s")
                        }
                    } catch (error: Throwable) {
                        errors.add(error)
                    }
                }
            reader.start()
            start.countDown()
            repeat(30) { index ->
                val goal = coordinator.create("Goal $index", emptyList(), GoalBudgets(5, 5, 10000, 60000, 10000, 0))
                val turn = requireNotNull(coordinator.start(request(goal, "race-$index")))
                turn.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
                storage.withTransaction { storage.goals.delete(goal) }
            }
            reader.join(10000)
            assertFalse("Snapshot reader did not finish", reader.isAlive)
            assertTrue(errors.joinToString(), errors.isEmpty())
        }

    private fun request(
        goal: String,
        turn: String,
    ) = GoalTurnStart(
        goal,
        GoalWakeReason.USER_OPEN,
        TurnStartSpec("s", turn, "m-$turn", "snapshot", "input"),
        TurnBudgets(5, 10, 800, 800, 5000),
    )

    private fun fixture(block: (HelixStorage, GoalRunCoordinator, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-blocker-${id()}.db"
        try {
            HelixStorage.open(context, name, java.io.File(context.cacheDir, name)).withClose { storage ->
                storage.sessions.create("s", "Fixture", null, null, 1000)
                val coordinator = GoalRunCoordinator(storage, clock, ::id)
                val goal = coordinator.create("Fixture", listOf("Evidence"), GoalBudgets(5, 5, 10000, 60000, 10000, 0))
                block(storage, coordinator, goal)
            }
        } finally {
            context.deleteDatabase(name)
            java.io.File(context.cacheDir, name).deleteRecursively()
        }
    }

    private fun HelixStorage.withClose(block: (HelixStorage) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
}
