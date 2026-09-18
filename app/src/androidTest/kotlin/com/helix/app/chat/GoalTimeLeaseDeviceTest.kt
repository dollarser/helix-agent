package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.GoalRunSettlement
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnStartSpec
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
class GoalTimeLeaseDeviceTest {
    @Test fun cumulativeCheckpointsConsumeOneAllocationAndReleaseUnusedCapacity() =
        Fixture().use { f ->
            assertTrue(f.reserve())
            assertTrue(f.journal.checkpointLease("lease", 2_000, 3_000))
            assertFalse(f.journal.checkpointLease("lease", 2_000, 3_001))
            assertFalse(f.journal.checkpointLease("lease", 1_000, 3_002))
            assertTrue(f.journal.checkpointLease("lease", 4_000, 4_000))
            assertEquals(
                4_000L,
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .reservedMillis,
            )
            assertEquals(
                4_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            // A Job may have ended before the latest overlapping Turn checkpoint.
            assertTrue(f.journal.checkpointLease("lease", 3_000, 4_001, terminal = true))
            assertEquals(
                4_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertFalse(f.journal.checkpointLease("lease", 9_000, 4_002, terminal = true))
            assertTrue(f.journal.reserve(f.request("ordinary", GoalUsageReservations.Kind.TIME, 5_000)))
        }

    @Test fun leaseCannotOverlapAnotherClockOrOversubscribeTheWakeBudget() =
        Fixture().use { f ->
            assertFalse(f.reserve(10_001))
            assertThrows(IllegalArgumentException::class.java) { f.reserve(GoalUsageReservations.MAX_LEASE_MILLIS + 1) }
            assertTrue(f.journal.reserve(f.request("ordinary", GoalUsageReservations.Kind.TIME, 1_000)))
            assertFalse(f.reserve())
            assertTrue(f.journal.settle("ordinary", 0, 500, 3_000))
            assertTrue(f.reserve())
            assertFalse(f.journal.reserve(f.request("another", GoalUsageReservations.Kind.TIME_LEASE, 1_000)))
            assertFalse(f.journal.reserve(f.request("duplicate-clock", GoalUsageReservations.Kind.TIME, 1_000)))
        }

    @Test fun completedTurnDoesNotChargeOrCloseItsLiveLease() =
        Fixture().use { f ->
            assertTrue(f.reserve())
            assertTrue(f.journal.checkpointLease("lease", 2_000, 3_000))
            f.started.coordinator.beginModelStream()
            f.started.coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            assertNull(
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .endedAt,
            )
            assertEquals(
                "RUNNING",
                f.storage.goals
                    .resolve(f.goalId)
                    .state,
            )
            assertEquals(
                "PENDING",
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .state,
            )
            assertEquals(
                2_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertTrue(f.journal.checkpointLease("lease", 7_000, 8_000, terminal = true))
            GoalRunSettlement(f.storage, f.clock) { UUID.randomUUID().toString() }.settle(f.started.coordinator.id)
            assertNotNull(
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .endedAt,
            )
            assertEquals(
                "PAUSED",
                f.storage.goals
                    .resolve(f.goalId)
                    .state,
            )
            assertEquals(
                7_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
        }

    @Test fun processRecoveryConsumesOnlyRemainingAllocationOnce() =
        Fixture().use { f ->
            assertTrue(f.reserve())
            assertTrue(f.journal.checkpointLease("lease", 2_000, 3_000))
            f.reopen()
            RecoveryCoordinatorApp(f.storage, f.clock).recover()
            assertEquals(
                8_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            val recovered = f.storage.goalUsageReservations.byId("lease")!!
            assertEquals("INTERRUPTED", recovered.state)
            assertEquals(8_000L, recovered.chargedMillis)
            RecoveryCoordinatorApp(f.storage, f.clock).recover()
            assertFalse(f.journal.checkpointLease("lease", 4_000, 9_000, terminal = true))
            assertEquals(
                8_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
        }

    @Test fun observedOverrunIsRecordedAndBudgetClosureWaitsForSettlement() =
        Fixture().use { f ->
            assertTrue(f.reserve())
            assertTrue(f.journal.checkpointLease("lease", 12_000, 13_000))
            assertEquals(
                12_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertEquals(
                0L,
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .reservedMillis,
            )
            assertNull(
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .endedAt,
            )
            assertTrue(f.journal.checkpointLease("lease", 12_000, 13_001, terminal = true))
            assertEquals(
                "BLOCKED",
                f.storage.goals
                    .resolve(f.goalId)
                    .state,
            )
            assertEquals(
                "BUDGET_EXHAUSTED(maxWakeDurationMillis)",
                f.storage.goalRuns
                    .resolve(f.started.runId)
                    .outcome,
            )
        }

    @Test fun failedCheckpointRollsBackBothUsageAndReservation() =
        Fixture().use { f ->
            assertTrue(f.reserve())
            val before = f.storage.goalUsageReservations.byId("lease")
            assertThrows(IllegalArgumentException::class.java) { f.journal.checkpointLease("lease", 1_000, -1) }
            assertEquals(before, f.storage.goalUsageReservations.byId("lease"))
            assertEquals(
                0L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
            assertTrue(f.journal.checkpointLease("lease", 1_000, 3_000))
            assertEquals(
                1_000L,
                f.storage.goals
                    .resolve(f.goalId)
                    .runTimeMillis,
            )
        }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "goal-lease-${UUID.randomUUID()}.db"
        private val content = File(context.cacheDir, "goal-lease-${UUID.randomUUID()}")
        var storage = HelixStorage.open(context, name, content)
        val journal get() = GoalUsageReservations(storage)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2_000)
            }
        val goalId: String
        val started: StartedGoalTurn

        init {
            storage.sessions.create("session", "Lease", null, null, 1_000)
            val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
            goalId =
                coordinator.create(
                    "Check result",
                    listOf("Result received"),
                    GoalBudgets(5, 5, 1_000, 60_000, 10_000, 0),
                )
            started =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goalId,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "turn", "model", "snapshot", "run"),
                            TurnBudgets(5, 5, 800, 800, 5_000),
                        ),
                    ),
                )
        }

        fun request(
            id: String,
            kind: GoalUsageReservations.Kind,
            millis: Long,
        ) = GoalUsageReservations.Request(id, started.runId, kind, millis = millis)

        fun reserve(millis: Long = 8_000) =
            journal.reserve(request("lease", GoalUsageReservations.Kind.TIME_LEASE, millis))

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
}
