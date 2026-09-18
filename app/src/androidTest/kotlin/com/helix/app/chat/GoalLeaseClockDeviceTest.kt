package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.GoalLeaseAllocation
import com.helix.app.agent.GoalTimeBudget
import com.helix.app.agent.TurnStartSpec
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GoalLeaseClockDeviceTest {
    @Test fun transferChargesPriorTimeAndLeavesOnlyTheJobsUnspentAllocationOnFinish() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            f.now = 400
            val allocation = requireNotNull(f.timer.transferToLease("lease", 8_000))
            assertEquals(8_000L, allocation.durationMs)
            assertEquals(400L, allocation.startedElapsedMs)
            f.now = 1_400
            assertTrue(f.timer.pulse())
            assertEquals(1_300L, f.used())
            f.now = 2_400
            f.timer.finish()
            f.timer.finish()
            assertEquals(2_300L, f.used())
            val pending =
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .single()
            assertEquals("TIME_LEASE", pending.kind)
            assertEquals(6_000L, pending.reservedMillis)
            assertEquals(2_000L, pending.chargedMillis)
            assertEquals(0L, f.timer.remainingExecutionMillis())
        }

    @Test fun failedTransferRollsBackTheOriginalClockAndUsage() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            val original =
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .single()
            f.now = 500
            assertNull(f.timer.transferToLease(original.id, 5_000))
            assertEquals(original, f.storage.goalUsageReservations.byId(original.id))
            assertEquals(0L, f.used())
            f.now = 600
            assertTrue(f.timer.pulse())
            assertEquals(500L, f.used())
        }

    @Test fun completedJobReturnsAnActiveTurnToOrdinaryWindowsWithoutDoubleCharging() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            val allocation = requireNotNull(f.timer.transferToLease("lease", 5_000))
            f.now = 1_100
            assertTrue(f.timer.pulse())
            f.now = 2_100
            assertFalse(f.timer.completeLease(allocation.copy(id = "foreign")))
            assertTrue(f.timer.completeLease(allocation))
            assertFalse(f.timer.completeLease(allocation))
            assertEquals(
                "SETTLED",
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .state,
            )
            assertEquals(
                "TIME",
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .single()
                    .kind,
            )
            f.now = 3_100
            assertTrue(f.timer.pulse())
            f.now = 4_100
            f.timer.finish()
            assertEquals(4_000L, f.used())
        }

    @Test fun goalHeartbeatMayContinueButCannotExtendTheRuntimeAllocation() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            val allocation = requireNotNull(f.timer.transferToLease("lease", 2_000))
            f.now = 1_100
            assertTrue(f.timer.pulse())
            f.now = 3_100
            assertTrue(f.timer.pulse())
            assertNull(f.timer.expiredCode())
            assertEquals(2_000L, allocation.durationMs)
            f.now = 4_100
            f.timer.finish()
            assertEquals(4_000L, f.used())
            assertEquals(
                0L,
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .reservedMillis,
            )
            assertEquals(
                "PENDING",
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .state,
            )
        }

    @Test fun allocationCannotExceedTheOriginalGoalDeadline() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            f.now = 600
            assertTrue(f.timer.pulse())
            val allocation = requireNotNull(f.timer.transferToLease("lease", 30_000))
            assertEquals(9_500L, allocation.durationMs)
            f.now = 10_100
            assertEquals("GOAL_BUDGET_LIMIT", f.timer.expiredCode())
            assertFalse(f.timer.pulse())
            assertFalse(f.timer.completeLease(allocation))
            f.timer.finish()
            assertEquals(10_000L, f.used())
            assertEquals(
                0L,
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .reservedMillis,
            )
        }

    @Test fun unstartedOrExpiredWindowsNeverAllocate() =
        Fixture().use { f ->
            assertNull(f.timer.transferToLease("before-start", 1_000))
            assertTrue(f.timer.start())
            f.now = 5_100
            assertNull(f.timer.transferToLease("expired", 1_000))
            assertNull(f.storage.goalUsageReservations.byId("expired"))
            assertEquals("GOAL_TIME_WINDOW_EXPIRED", f.timer.expiredCode())
        }

    @Test fun aShortJobDoesNotShortenTheActiveGoalsHeartbeatWindow() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            val allocation = requireNotNull(f.timer.transferToLease("lease", 1_000))
            assertEquals(
                5_000L,
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .reservedMillis,
            )
            f.now = 1_600
            assertNull(f.timer.expiredCode())
            assertTrue(f.timer.pulse())
            assertEquals(1_000L, allocation.durationMs)
            f.timer.finish()
            assertEquals(1_500L, f.used())
            assertEquals(
                0L,
                f.storage.goalUsageReservations
                    .byId("lease")!!
                    .reservedMillis,
            )
        }

    @Test fun heartbeatRacingTransferStillHasOneClockOwner() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            f.now = 1_000
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val pulse =
                    pool.submit<Boolean> {
                        start.await()
                        f.timer.pulse()
                    }
                val transfer =
                    pool.submit<GoalLeaseAllocation?> {
                        start.await()
                        f.timer.transferToLease("lease", 8_000)
                    }
                start.countDown()
                assertTrue(pulse.get(10, TimeUnit.SECONDS))
                assertNotNull(transfer.get(10, TimeUnit.SECONDS))
                assertEquals(900L, f.used())
                assertEquals(
                    "TIME_LEASE",
                    f.storage.goalUsageReservations
                        .pendingForRun(f.runId)
                        .single()
                        .kind,
                )
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "goal-clock-${UUID.randomUUID()}.db"
        private val content = File(context.cacheDir, "goal-clock-${UUID.randomUUID()}")
        val storage = HelixStorage.open(context, name, content)

        @Volatile var now = 100L
        val runId: String
        private val goalId: String
        val timer: GoalTimeBudget

        init {
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(2_000)
                }
            storage.sessions.create("session", "Clock", null, null, 1_000)
            val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
            goalId =
                coordinator.create(
                    "Check result",
                    listOf("Result received"),
                    GoalBudgets(5, 5, 1_000, 60_000, 10_000, 0),
                )
            val started =
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
            runId = started.runId
            timer = GoalTimeBudget(storage, clock, runId) { now }
        }

        fun used() = storage.goals.resolve(goalId).runTimeMillis

        override fun close() {
            timer.finish()
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
