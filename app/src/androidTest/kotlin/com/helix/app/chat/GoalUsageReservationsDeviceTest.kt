package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.recovery.GoalUsageReservations
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalUsageReservationsDeviceTest {
    @Test
    fun goalSummaryUsesPersistedStateAndExcludesAnotherSessionsBoundGoal() =
        withFixture { f ->
            val query = GoalSummaryQuery(f.storage)
            assertFalse(query.forSession("session").single().canContinue)
            val running = query.forSession("session").single()
            assertFalse(running.canDelete)
            val original = f.storage.goals.resolve(running.id)
            f.storage.goals.updateGoal(original.copy(state = "PAUSED"))
            assertFalse(query.forSession("session").single().canDelete)
            f.storage.goals.updateGoal(original)
            f.started.coordinator.beginModelStream()
            f.started.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            val paused = query.forSession("session").single()
            assertTrue(paused.canContinue)
            assertTrue(paused.canEditBudgets)
            assertTrue(paused.canDelete)
            assertEquals("PAUSED", paused.status.state)
            assertEquals("RUN_FINISHED", paused.status.outcome)
            assertEquals(0, paused.satisfiedCriteria)
            f.storage.sessions.create("other", "Other", null, null, 1_000)
            assertTrue(query.forSession("other").isEmpty())
            val ready =
                f.coordinator().create(
                    "New goal",
                    listOf("Checked"),
                    GoalBudgets(2, 4, 1_000, 60_000, 10_000, 0),
                )
            val draft = query.forSession("session").single { it.id == ready }
            assertTrue(draft.canContinue)
            assertFalse(draft.canEditBudgets)
            assertTrue(draft.canDelete)
        }

    @Test
    fun outstandingReservationsConsumeCapacityAndSettlementIsExactlyOnce() =
        withFixture { f ->
            val journal = GoalUsageReservations(f.storage)
            assertTrue(journal.reserve(f.model("first", 600)))
            assertFalse(journal.reserve(f.model("first", 600)))
            assertFalse(journal.reserve(f.model("second", 500)))
            assertTrue(journal.reserve(f.model("second", 400)))
            assertEquals(
                0L,
                f.storage.goals
                    .resolve(f.goalId)
                    .totalTokens,
            )
            assertTrue(journal.settle("first", 100, 0, 2_001))
            assertNull(
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .endedAt,
            )
            assertTrue(journal.settle("second", 200, 0, 2_002))
            val goal = f.storage.goals.resolve(f.goalId)
            assertEquals(2, goal.modelCalls)
            assertEquals(300L, goal.totalTokens)
            assertEquals("PAUSED", goal.state)
            assertEquals(
                "BUDGET_EXHAUSTED(maxModelCalls)",
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .outcome,
            )
            assertFalse(journal.settle("second", 999, 0, 2_003))
            assertEquals(goal, f.storage.goals.resolve(f.goalId))
            assertFalse(journal.reserve(f.model("third", 1)))
        }

    @Test
    fun reopenAndRepeatedRecoveryChargeUnknownUsageWithoutOfflineTimeOrReplay() =
        withFixture { f ->
            val journal = GoalUsageReservations(f.storage)
            assertTrue(journal.reserve(f.model("model", 600)))
            assertTrue(journal.reserve(f.reservation("tool", GoalUsageReservations.Kind.TOOL)))
            assertTrue(journal.reserve(f.reservation("time", GoalUsageReservations.Kind.TIME, millis = 5_000)))
            f.reopen()
            val backwards =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(1)
                }
            RecoveryCoordinatorApp(f.storage, backwards).recover()
            val goal = f.storage.goals.resolve(f.goalId)
            assertEquals(1, goal.modelCalls)
            assertEquals(1, goal.toolCalls)
            assertEquals(600L, goal.totalTokens)
            assertEquals(5_000L, goal.runTimeMillis)
            assertEquals(0L, goal.currentWakeMillis)
            assertEquals("PAUSED", goal.state)
            assertEquals(
                "INTERRUPTED",
                f.storage.goalUsageReservations
                    .byId("model")
                    ?.state,
            )
            assertTrue(
                f.storage.goalUsageReservations
                    .pendingForRun(f.started.runId)
                    .isEmpty(),
            )
            RecoveryCoordinatorApp(
                f.storage,
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(999_999_999)
                },
            ).recover()
            assertEquals(goal, f.storage.goals.resolve(f.goalId))
            f.started = requireNotNull(f.coordinator().start(f.request("second")))
            val recovered = GoalUsageReservations(f.storage)
            assertFalse(recovered.reserve(f.model("too-large", 401)))
            assertTrue(recovered.reserve(f.model("last", 400)))
            f.reopen()
            val report = RecoveryCoordinatorApp(f.storage, backwards).recover()
            assertTrue(report.closedRuns.contains(f.started.runId))
            assertEquals(
                1_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .totalTokens,
            )
            assertEquals(
                2,
                f.storage.goals
                    .resolve(f.goalId)
                    .modelCalls,
            )
            assertNull(f.coordinator().start(f.request("third")))
        }

    @Test
    fun failedSettlementRollsBackAndTerminalCancellationReconcilesPendingUsage() =
        withFixture { f ->
            val journal = GoalUsageReservations(f.storage)
            assertTrue(journal.reserve(f.model("pending", 200)))
            assertThrows(IllegalArgumentException::class.java) { journal.settle("pending", 10, 0, -1) }
            assertEquals(
                "PENDING",
                f.storage.goalUsageReservations
                    .byId("pending")
                    ?.state,
            )
            assertEquals(
                0L,
                f.storage.goals
                    .resolve(f.goalId)
                    .totalTokens,
            )
            f.started.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            assertEquals(
                "CANCELLED",
                f.storage.goals
                    .resolve(f.goalId)
                    .state,
            )
            assertEquals(
                200L,
                f.storage.goals
                    .resolve(f.goalId)
                    .totalTokens,
            )
            assertNotNull(
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .endedAt,
            )
            assertFalse(journal.settle("pending", 5, 0, 2_100))
            assertFalse(journal.reserve(f.model("late", 1)))
        }

    @Test
    fun timeReservationsShareWakeCapacityAndReleaseOnlyKnownUnusedTime() =
        withFixture { f ->
            val journal = GoalUsageReservations(f.storage)
            assertThrows(IllegalArgumentException::class.java) {
                journal.reserve(f.reservation("oversized", GoalUsageReservations.Kind.TIME, millis = 5_001))
            }
            assertTrue(journal.reserve(f.reservation("first", GoalUsageReservations.Kind.TIME, millis = 5_000)))
            assertTrue(journal.reserve(f.reservation("second", GoalUsageReservations.Kind.TIME, millis = 5_000)))
            assertFalse(journal.reserve(f.reservation("full", GoalUsageReservations.Kind.TIME, millis = 1)))
            assertTrue(journal.settle("first", 0, 100, 2_100))
            assertFalse(journal.reserve(f.reservation("too-large", GoalUsageReservations.Kind.TIME, millis = 5_000)))
            assertTrue(journal.reserve(f.reservation("remaining", GoalUsageReservations.Kind.TIME, millis = 4_900)))
            RecoveryCoordinatorApp(f.storage, f.clock).recover()
            assertEquals(
                10_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertEquals(
                "BUDGET_EXHAUSTED(maxWakeDurationMillis)",
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .outcome,
            )
        }

    @Test
    fun concurrentAdmissionsCannotOversubscribeRemainingTokens() =
        withFixture { f ->
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(3)
            val start = java.util.concurrent.CountDownLatch(1)
            try {
                val futures =
                    (1..3).map { index ->
                        executor.submit<Boolean> {
                            start.await()
                            GoalUsageReservations(f.storage).reserve(f.model("parallel-$index", 600))
                        }
                    }
                start.countDown()
                val admitted = futures.count { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, admitted)
                assertEquals(
                    1,
                    f.storage.goalUsageReservations
                        .pendingForRun(f.started.runId)
                        .size,
                )
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }

    @Test
    fun timeWindowRenewalUsesMonotonicElapsedAndFinishIsIdempotent() =
        withFixture { f ->
            var elapsed = 0L
            val timer = GoalTimeBudget(f.storage, f.clock, f.started.runId) { elapsed }
            assertTrue(timer.start())
            assertEquals(10_000L, timer.remainingExecutionMillis())
            elapsed = 1_000
            assertTrue(timer.pulse())
            assertEquals(9_000L, timer.remainingExecutionMillis())
            assertEquals(
                1_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertEquals(
                1,
                f.storage.goalUsageReservations
                    .pendingForRun(f.started.runId)
                    .size,
            )
            elapsed = 1_300
            timer.finish()
            timer.finish()
            assertEquals(0L, timer.remainingExecutionMillis())
            assertEquals(
                1_300L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertTrue(
                f.storage.goalUsageReservations
                    .pendingForRun(f.started.runId)
                    .isEmpty(),
            )
        }

    @Test
    fun delayedTimeWindowStopsAndRecordsTheEntireObservedOverrun() =
        withFixture { f ->
            var elapsed = 0L
            val timer = GoalTimeBudget(f.storage, f.clock, f.started.runId) { elapsed }
            assertTrue(timer.start())
            elapsed = 6_500
            assertFalse(timer.pulse())
            assertEquals("GOAL_TIME_WINDOW_EXPIRED", timer.expiredCode())
            assertEquals(0L, timer.remainingExecutionMillis())
            timer.finish()
            assertEquals(
                6_500L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertEquals(
                6_500L,
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .wakeDurationMillis,
            )
            f.started.coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "GOAL_TIME_WINDOW_EXPIRED"))
            assertEquals(
                "INTERRUPTED",
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .outcome,
            )
            assertEquals(
                "PAUSED",
                f.storage.goals
                    .resolve(f.goalId)
                    .state,
            )
        }

    @Test
    fun renewedWindowSurvivesReopenWithoutChargingTheOfflineInterval() =
        withFixture { f ->
            var elapsed = 0L
            val timer = GoalTimeBudget(f.storage, f.clock, f.started.runId) { elapsed }
            assertTrue(timer.start())
            elapsed = 1_200
            assertTrue(timer.pulse())
            f.reopen()
            val later =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(999_999_999)
                }
            RecoveryCoordinatorApp(f.storage, later).recover()
            assertEquals(
                6_200L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            val recovered = f.storage.goals.resolve(f.goalId)
            RecoveryCoordinatorApp(f.storage, later).recover()
            assertEquals(recovered, f.storage.goals.resolve(f.goalId))
        }

    private class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-reservation-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "goal-reservation-${UUID.randomUUID()}")
        var storage = HelixStorage.open(context, name, content)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2_000)
            }
        val goalId: String
        var started: StartedGoalTurn

        init {
            storage.sessions.create("session", "Goal", null, null, 1_000)
            goalId =
                coordinator().create(
                    "Check output",
                    listOf("Verified output exists"),
                    GoalBudgets(2, 4, 1_000, 60_000, 10_000, 0),
                )
            started = requireNotNull(coordinator().start(request("first")))
        }

        fun coordinator() = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }

        fun request(turnId: String) =
            GoalTurnStart(
                goalId,
                GoalWakeReason.USER_OPEN,
                TurnStartSpec("session", turnId, "model-$turnId", "snapshot", "run"),
                TurnBudgets(5, 10, 800, 800, 5_000),
            )

        fun reservation(
            id: String,
            kind: GoalUsageReservations.Kind,
            tokens: Long = 0,
            millis: Long = 0,
        ) = GoalUsageReservations.Request(id, started.runId, kind, tokens, millis)

        fun model(
            id: String,
            tokens: Long,
        ) = reservation(id, GoalUsageReservations.Kind.MODEL, tokens)

        fun reopen() {
            storage.close()
            storage = HelixStorage.open(context, name, content)
        }

        override fun close() {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) = Fixture().use(block)
}
