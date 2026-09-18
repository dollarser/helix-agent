package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.agent.GoalDetachedBudget
import com.helix.app.agent.GoalTimeBudget
import com.helix.app.agent.TurnStartSpec
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

class GoalDetachedBudgetDeviceTest {
    @Test fun activeNoStartRestoresOrdinaryClockAndNeverChargesTheIntervalTwice() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            f.now = 400
            assertEquals(8_000L, f.bridge.prepare("session", "turn", "execution", 8_000))
            assertEquals(
                1,
                f.storage.auditEvents.listByCorrelation("session").count {
                    it.type ==
                        "proot.goal_lease_prepared"
                },
            )
            f.now = 800
            f.bridge.reject("session", "turn", "execution")
            assertEquals(700L, f.used())
            assertEquals(
                "TIME",
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .single()
                    .kind,
            )
            f.bridge.reject("session", "turn", "execution")
            assertEquals(700L, f.used())
        }

    @Test fun lateNoStartSettlesOriginalTerminatedTurnWithoutStartingAnotherRun() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            assertEquals(8_000L, f.bridge.prepare("session", "turn", "execution", 8_000))
            f.now = 800
            f.timer.finish()
            f.live = false
            f.storage.turns.updateState(f.storage.turns.resolve("turn"), TurnState.CANCELLING, 0, null, null)
            f.storage.turns.updateState(f.storage.turns.resolve("turn"), TurnState.CANCELLED, 0, 2_000, null)
            f.now = 1_000
            f.bridge.reject("session", "turn", "execution")
            assertEquals(900L, f.used())
            assertTrue(
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .isEmpty(),
            )
            assertNotNull(
                f.storage.goalRuns
                    .resolve(f.runId)
                    .endedAt,
            )
        }

    @Test fun missingLiveGoalClockCannotFallBackToUnbudgetedWork() =
        Fixture().use { f ->
            f.live = false
            assertEquals(0L, f.bridge.prepare("session", "turn", "execution", 8_000))
            f.bridge.reject("session", "turn", "execution")
            assertTrue(
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .isEmpty(),
            )
        }

    @Test fun foreignSessionCannotAllocateOrReleaseTheOriginalGoalLease() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            assertThrows(IllegalStateException::class.java) { f.bridge.prepare("other", "turn", "execution", 8_000) }
            assertEquals(8_000L, f.bridge.prepare("session", "turn", "execution", 8_000))
            assertThrows(IllegalStateException::class.java) { f.bridge.reject("other", "turn", "execution") }
            assertEquals(
                "TIME_LEASE",
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .single()
                    .kind,
            )
        }

    @Test fun leaseMetadataFailureRollsBackBothTheLedgerAndLiveClock() =
        Fixture().use { f ->
            assertTrue(f.timer.start())
            f.now = 400
            assertThrows(IOException::class.java) {
                f.timer.transferToLease("lease", 8_000) { throw IOException("metadata write failed") }
            }
            assertEquals(0L, f.used())
            assertEquals(
                "TIME",
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .single()
                    .kind,
            )
            assertTrue(f.timer.pulse())
            assertEquals(300L, f.used())
            assertFalse(
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .any { it.id == "lease" },
            )
        }

    @Test fun ordinaryTurnUsesItsApprovedWindowWithoutCreatingGoalAccounting() =
        Fixture().use { f ->
            f.storage.turns.start("ordinary", "session", 2_000)
            assertEquals(8_000L, f.bridge.prepare("session", "ordinary", "execution", 8_000))
            f.bridge.reject("session", "ordinary", "execution")
            assertTrue(
                f.storage.goalUsageReservations
                    .pendingForRun(f.runId)
                    .isEmpty(),
            )
        }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "goal-detached-${UUID.randomUUID()}"
        private val content = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, content)
        var now = 100L
        var live = true
        private val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2_000)
            }
        val runId: String
        private val goalId: String
        val timer: GoalTimeBudget
        val bridge: GoalDetachedBudget

        init {
            storage.sessions.create("session", "Detached goal", null, null, 1_000)
            val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
            goalId = coordinator.create("Result", listOf("Verified"), GoalBudgets(5, 5, 1_000, 60_000, 10_000, 0))
            runId =
                requireNotNull(
                    coordinator.start(
                        GoalTurnStart(
                            goalId,
                            GoalWakeReason.USER_OPEN,
                            TurnStartSpec("session", "turn", "model", "snapshot", "run"),
                            TurnBudgets(5, 5, 800, 800, 5_000),
                        ),
                    ),
                ).runId
            timer = GoalTimeBudget(storage, clock, runId) { now }
            bridge =
                GoalDetachedBudget(
                    storage,
                    clock,
                    { if (live) timer else null },
                    { UUID.randomUUID().toString() },
                    { now },
                )
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
