package com.helix.app.infrastructure.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.TurnStartSpec
import com.helix.app.recovery.GoalUsageReservations
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Room tests: command boundaries must compose with admission and plan transactions. */
@RunWith(AndroidJUnit4::class)
class RoomGoalCommandsTransactionDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(2_000)
        }
    private val budgets = GoalBudgets(maxGoalRounds = 10)

    @Test
    fun outerFailureRollsBackCreationAndAudit() =
        withStorage { storage ->
            val commands = commands(storage)
            var goalId: String? = null
            var correlation: String? = null
            assertThrows(IllegalStateException::class.java) {
                storage.withTransaction {
                    goalId = commands.create("atomic creation", emptyList(), budgets)
                    correlation = storage.goals.resolve(requireNotNull(goalId)).correlationId
                    error("outer plan transition failed")
                }
            }
            assertNull(storage.goals.find(requireNotNull(goalId)))
            assertTrue(storage.auditEvents.listByCorrelation(requireNotNull(correlation)).isEmpty())
        }

    @Test
    fun outerFailureRollsBackRunTurnBindingAndAudit() =
        withStorage { storage ->
            val commands = commands(storage)
            val goalId = commands.create("atomic admission", emptyList(), budgets)
            val before = storage.goals.resolve(goalId)
            val audit = storage.auditEvents.listByCorrelation(before.correlationId)
            assertThrows(IllegalStateException::class.java) {
                storage.withTransaction {
                    assertNotNull(commands.start(request(goalId, "rolled-back")))
                    error("outer runtime record failed")
                }
            }
            assertEquals(before, storage.goals.resolve(goalId))
            assertTrue(storage.goalRuns.listByGoal(goalId).isEmpty())
            assertNull(storage.turns.find("rolled-back"))
            assertNull(storage.goalTurnBindings.byTurn("rolled-back"))
            assertEquals(audit, storage.auditEvents.listByCorrelation(before.correlationId))
        }

    @Test
    fun concurrentWakeCommitsOnlyOneRunAndTurn() =
        withStorage { storage ->
            val goalId = commands(storage).create("one wake", emptyList(), budgets)
            val starts = race { index -> commands(storage).start(request(goalId, "wake-$index")) }
            assertEquals(1, starts.count { it != null })
            assertEquals(1, storage.goalRuns.listByGoal(goalId).size)
            assertEquals(1, storage.goalTurnBindings.turnsForGoal(goalId).size)
            assertEquals(1, storage.goals.resolve(goalId).runCount)
        }

    @Test
    fun concurrentBudgetRevisionCommitsOneUpdateAndAudit() =
        withStorage { storage ->
            val goalId = commands(storage).create("one edit", emptyList(), budgets)
            RoomGoalOwnership(storage).bind(goalId, "session")
            val changed =
                race { index ->
                    commands(storage).updateBudgets(
                        goalId,
                        budgets.copy(maxGoalRounds = 20 + index),
                        expectedRevision = 0,
                    )
                }
            assertEquals(1, changed.count { it })
            assertEquals(1L, storage.goalControls.find(goalId)?.revision)
            val goal = storage.goals.resolve(goalId)
            assertEquals(20 + changed.indexOf(true), goal.budgets.maxGoalRounds)
            assertEquals(
                1,
                storage.auditEvents
                    .listByCorrelation(goal.correlationId)
                    .count { it.type == "goal.budgets_updated" },
            )
            assertTrue(storage.goalRuns.listByGoal(goalId).isEmpty())
        }

    @Test
    fun failedInspectionRollsBackOriginalReservationAndSuccessfulRetryChargesOnce() =
        withStorage { storage ->
            val commands = commands(storage)
            val goalId = commands.create("inspect unknown effects", emptyList(), budgets)
            val first = requireNotNull(commands.start(request(goalId, "original")))
            val reservations = GoalUsageReservations(storage)
            assertTrue(
                reservations.reserve(
                    GoalUsageReservations.Request("unknown-tool", first.runId, GoalUsageReservations.Kind.TOOL),
                ),
            )
            // Recovery must charge unknown allocations while RUNNING, then park/close the run.
            // Compose real recovery and inspection in an outer transaction to check their rollback.
            val beforeGoal = storage.goals.resolve(goalId)
            val beforeRun = storage.goalRuns.resolve(first.runId)
            val beforeReservation = storage.goalUsageReservations.byId("unknown-tool")
            val inspection =
                request(goalId, "inspection").let {
                    it.copy(turn = it.turn.copy(recoveryFromTurnId = "original"), recoveryInspection = true)
                }
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                storage.withTransaction {
                    TurnRecovery(storage, clock).recover()
                    assertEquals(1, storage.goals.resolve(goalId).toolCalls)
                    assertNotNull(storage.goalRuns.resolve(first.runId).endedAt)
                    commands.start(inspection.copy(turn = inspection.turn.copy(sessionId = "absent")))
                }
            }
            assertEquals(beforeGoal, storage.goals.resolve(goalId))
            assertEquals(beforeRun, storage.goalRuns.resolve(first.runId))
            assertEquals(beforeReservation, storage.goalUsageReservations.byId("unknown-tool"))
            assertNull(storage.turns.find("inspection"))
            storage.withTransaction {
                TurnRecovery(storage, clock).recover()
                assertNotNull(commands.start(inspection))
            }
            assertEquals(1, storage.goalRuns.resolve(first.runId).toolCalls)
            assertEquals(1, storage.goals.resolve(goalId).toolCalls)
            reservations.recoverRun(first.runId, 2_000)
            assertEquals(1, storage.goals.resolve(goalId).toolCalls)
        }

    @Test
    fun standaloneInspectionFailureRollsBackItsGoalRunTurnAndAudit() =
        withStorage { storage ->
            val commands = commands(storage)
            val goalId = commands.create("inspect after recovery", emptyList(), budgets)
            assertNotNull(commands.start(request(goalId, "original")))
            TurnRecovery(storage, clock).recover()
            val beforeGoal = storage.goals.resolve(goalId)
            val beforeRuns = storage.goalRuns.listByGoal(goalId)
            val beforeGoalAudit = storage.auditEvents.listByCorrelation(beforeGoal.correlationId)
            val beforeSessionAudit = storage.auditEvents.listByCorrelation("session")
            val inspection =
                request(goalId, "inspection").let {
                    it.copy(turn = it.turn.copy(recoveryFromTurnId = "original"), recoveryInspection = true)
                }
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                commands.start(inspection.copy(turn = inspection.turn.copy(sessionId = "absent")))
            }
            assertEquals(beforeGoal, storage.goals.resolve(goalId))
            assertEquals(beforeRuns, storage.goalRuns.listByGoal(goalId))
            assertNull(storage.turns.find("inspection"))
            assertNull(storage.goalTurnBindings.byTurn("inspection"))
            assertEquals(beforeGoalAudit, storage.auditEvents.listByCorrelation(beforeGoal.correlationId))
            assertEquals(beforeSessionAudit, storage.auditEvents.listByCorrelation("session"))
            assertNotNull(commands.start(inspection))
            assertEquals(beforeRuns.size + 1, storage.goalRuns.listByGoal(goalId).size)
        }

    private fun commands(storage: HelixStorage) = RoomGoalRunCommands(storage, clock) { UUID.randomUUID().toString() }

    private fun request(
        goalId: String,
        turnId: String,
    ) = GoalTurnStart(
        goalId,
        GoalWakeReason.USER_OPEN,
        TurnStartSpec("session", turnId, "model-$turnId", "snapshot", "run"),
        TurnBudgets(maxOutputTokens = 800),
    )

    private fun <T> race(action: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val futures =
                (0..1).map { index ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            action(index)
                        },
                    )
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-commands-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "goal-commands-${UUID.randomUUID()}")
        val storage = HelixStorage.open(context, name, content)
        try {
            storage.sessions.create("session", "Goal", null, null, 1_000)
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
