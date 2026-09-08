package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.recovery.GoalDurableUsageLedger
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.TurnBudgets
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
class GoalRunCoordinatorDeviceTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(2_000)
        }
    private val budgets = GoalBudgets(2, 4, 1_000, 60_000, 10_000, 0)
    private val limits = TurnBudgets(5, 10, 800, 800, 5_000)

    @Test
    fun explicitContinueStartsExactlyOneBoundRun() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            assertEquals(GoalState.READY.name, storage.goals.resolve(id).state)
            assertTrue(storage.goalRuns.listByGoal(id).isEmpty())
            val started = requireNotNull(coordinator.start(request(id, "turn-1")))
            assertEquals(2, started.budgets.maxModelCalls)
            assertEquals(1_000L, started.budgets.maxTotalTokens)
            assertEquals(started.runId, storage.goalTurnBindings.byTurn("turn-1")?.runId)
            assertNull(coordinator.start(request(id, "turn-2")))
            assertEquals(1, storage.goalRuns.listByGoal(id).size)
            assertThrows(IllegalArgumentException::class.java) { storage.turns.resolve("turn-2") }
        }

    @Test
    fun recoveredGoalUsesRemainingBudgetAndKeepsOldRunClosed() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val first = requireNotNull(coordinator.start(request(id, "first")))
            GoalDurableUsageLedger(storage).checkpoint(
                id,
                first.runId,
                GoalDurableUsageLedger.Boundary.MODEL,
                GoalDurableUsageLedger.Delta(modelCalls = 1, tokens = 30, durationMillis = 100),
                2_100,
            )
            RecoveryCoordinatorApp(storage, clock).recover()
            val old = storage.goalRuns.resolve(first.runId)
            val second = requireNotNull(coordinator.start(request(id, "second")))
            assertEquals(1, second.budgets.maxModelCalls)
            assertEquals(970L, second.budgets.maxTotalTokens)
            assertEquals(old, storage.goalRuns.resolve(first.runId))
            assertNotNull(old.endedAt)
            assertEquals(2, storage.goals.resolve(id).runCount)
        }

    @Test
    fun budgetExtensionPersistsAndDoesNotImplicitlyStart() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val started = requireNotNull(coordinator.start(request(id, "first")))
            assertFalse(coordinator.updateBudgets(id, budgets.copy(maxModelCalls = 9)))
            RecoveryCoordinatorApp(storage, clock).recover()
            assertTrue(coordinator.updateBudgets(id, budgets.copy(maxModelCalls = 9)))
            val persisted = storage.goals.resolve(id)
            assertEquals(9, persisted.budgets.maxModelCalls)
            assertEquals(GoalState.PAUSED.name, persisted.state)
            assertEquals(1, storage.goalRuns.listByGoal(id).size)
            assertNotNull(storage.goalRuns.resolve(started.runId).endedAt)
            val freshCoordinator = coordinator(storage)
            assertEquals(9, requireNotNull(freshCoordinator.start(request(id, "second"))).budgets.maxModelCalls)
        }

    @Test
    fun reminderCheckpointPersistsWithoutStartingAnotherRun() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val checkpoint =
                com.helix.core.agent
                    .Checkpoint(99_000L)
            assertFalse(coordinator.setCheckpoint(id, checkpoint))
            val started = requireNotNull(coordinator.start(request(id, "first")))
            assertTrue(coordinator.setCheckpoint(id, checkpoint))
            RecoveryCoordinatorApp(storage, clock).recover()
            val paused = storage.goals.resolve(id)
            val closed = storage.goalRuns.resolve(started.runId)
            assertEquals(99_000L, paused.nextCheckpoint)
            assertTrue(coordinator(storage).setCheckpoint(id, null))
            assertEquals(paused.copy(nextCheckpoint = null), storage.goals.resolve(id))
            assertEquals(listOf(closed), storage.goalRuns.listByGoal(id))
            assertTrue(coordinator.setCheckpoint(id, checkpoint))
            assertEquals(paused, storage.goals.resolve(id))
        }

    @Test
    fun reminderReconciliationUsesDurableStateAndCancelsTerminalGoals() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val queue = mutableMapOf<String, Long>()
            val cancelled = mutableListOf<String>()
            val enqueuer =
                object : com.helix.app.goal.ReminderEnqueuer {
                    override fun enqueueOrReplace(
                        workName: String,
                        delayMillis: Long,
                        goalId: String,
                        objective: String,
                        checkpointEpochMillis: Long,
                    ) {
                        queue[workName] = delayMillis
                    }

                    override fun cancel(workName: String) {
                        queue.remove(workName)
                    }
                }
            val scheduler =
                com.helix.app.goal
                    .GoalReminderScheduler(enqueuer) { cancelled.add(it) }
            val reconciler =
                com.helix.app.goal
                    .GoalReminderReconciler(storage, scheduler, clock)
            val started = requireNotNull(coordinator.start(request(id, "first")))
            coordinator.setCheckpoint(
                id,
                com.helix.core.agent
                    .Checkpoint(99_000L),
            )
            reconciler.reconcileAll()
            assertEquals(listOf(97_000L), queue.values.toList())
            RecoveryCoordinatorApp(storage, clock).recover()
            val paused = storage.goals.resolve(id)
            reconciler.reconcileAll()
            assertEquals(paused, storage.goals.resolve(id))
            assertEquals(1, queue.size)
            assertEquals(1, storage.goalRuns.listByGoal(id).size)
            assertNotNull(storage.goalRuns.resolve(started.runId).endedAt)
            coordinator.setCheckpoint(id, null)
            reconciler.reconcile(id)
            assertTrue(queue.isEmpty())
            assertEquals(id, cancelled.last())
            coordinator.setCheckpoint(
                id,
                com.helix.core.agent
                    .Checkpoint(99_000L),
            )
            val next = requireNotNull(coordinator.start(request(id, "second")))
            next.coordinator.terminalize(ModelStreamTerminal(com.helix.core.model.TurnState.CANCELLED, null))
            reconciler.reconcileAll()
            assertTrue(queue.isEmpty())
            assertEquals(GoalState.CANCELLED.name, storage.goals.resolve(id).state)
            assertFalse(
                coordinator.setCheckpoint(
                    id,
                    com.helix.core.agent
                        .Checkpoint(100_000L),
                ),
            )
        }

    @Test
    fun invalidTurnRollsBackRunAndCreationRejectsMissingCriteria() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            assertThrows(IllegalArgumentException::class.java) { coordinator.create("Empty", emptyList(), budgets) }
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val before = storage.goals.resolve(id)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                coordinator.start(request(id, "bad").let { it.copy(turn = it.turn.copy(sessionId = "absent")) })
            }
            assertEquals(before, storage.goals.resolve(id))
            assertTrue(storage.goalRuns.listByGoal(id).isEmpty())
        }

    @Test
    fun normalTurnParksGoalAndContinueKeepsThePreviousOutcome() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val first = requireNotNull(coordinator.start(request(id, "first")))
            first.coordinator.beginModelStream()
            first.coordinator.terminalize(ModelStreamTerminal(com.helix.core.model.TurnState.COMPLETED, null))
            assertEquals(GoalState.PAUSED.name, storage.goals.resolve(id).state)
            val closed = storage.goalRuns.resolve(first.runId)
            assertEquals("RUN_FINISHED", closed.outcome)
            GoalRunSettlement(storage, clock) { UUID.randomUUID().toString() }.settle("first")
            assertEquals(closed, storage.goalRuns.resolve(first.runId))
            assertNotNull(coordinator.start(request(id, "second")))
            assertEquals(closed, storage.goalRuns.resolve(first.runId))
        }

    @Test
    fun cancelledAndFailedTurnsCloseGoalsAndCannotContinue() {
        for (state in listOf(com.helix.core.model.TurnState.CANCELLED, com.helix.core.model.TurnState.FAILED)) {
            withStorage { storage ->
                val coordinator = coordinator(storage)
                val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
                val started = requireNotNull(coordinator.start(request(id, "first")))
                started.coordinator.terminalize(ModelStreamTerminal(state, "INTERNAL"))
                assertEquals(state.name, storage.goals.resolve(id).state)
                assertEquals(state.name, storage.goalRuns.resolve(started.runId).outcome)
                assertNull(coordinator.start(request(id, "second")))
            }
        }
    }

    @Test
    fun uncertainSideEffectRequiresInputInsteadOfClaimingGoalCompletion() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val started = requireNotNull(coordinator.start(request(id, "first")))
            storage.toolCalls.append("uncertain", "first", "uncertain", "files.write", "1", "{}", "NEEDS_REVIEW")
            started.coordinator.beginModelStream()
            started.coordinator.terminalize(ModelStreamTerminal(com.helix.core.model.TurnState.COMPLETED, null))
            assertEquals(GoalState.INPUT_REQUIRED.name, storage.goals.resolve(id).state)
            assertEquals("INPUT_REQUIRED(NEEDS_REVIEW)", storage.goalRuns.resolve(started.runId).outcome)
            assertEquals("NEEDS_REVIEW", storage.toolCalls.resolve("uncertain").state)
            assertNull(coordinator.start(request(id, "blocked-continue")))
        }

    @Test
    fun staleRunSnapshotsCannotDecreaseDurableUsage() =
        withStorage { storage ->
            val coordinator = coordinator(storage)
            val id = coordinator.create("Check output", listOf("Verified output exists"), budgets)
            val started = requireNotNull(coordinator.start(request(id, "first")))
            val stale = storage.goalRuns.resolve(started.runId)
            val latest = storage.goalRuns.checkpointUsage(stale, 2, 3, 40, 500)
            val decreasing =
                listOf(
                    GoalDurableUsageLedger.Delta(1, 3, 40, 500),
                    GoalDurableUsageLedger.Delta(2, 2, 40, 500),
                    GoalDurableUsageLedger.Delta(2, 3, 39, 500),
                    GoalDurableUsageLedger.Delta(2, 3, 40, 499),
                )
            for (usage in decreasing) {
                assertThrows(IllegalArgumentException::class.java) {
                    storage.goalRuns.checkpointUsage(
                        stale,
                        usage.modelCalls,
                        usage.toolCalls,
                        usage.tokens,
                        usage.durationMillis,
                    )
                }
                assertEquals(latest, storage.goalRuns.resolve(started.runId))
                assertThrows(IllegalArgumentException::class.java) {
                    storage.goalRuns.finish(
                        stale,
                        "RUN_FINISHED",
                        3_000,
                        usage.durationMillis,
                        usage.modelCalls,
                        usage.toolCalls,
                        usage.tokens,
                    )
                }
                assertEquals(latest, storage.goalRuns.resolve(started.runId))
            }
            storage.goalRuns.finish(stale, "RUN_FINISHED", 3_000, 500, 2, 3, 40)
            val closed = storage.goalRuns.resolve(started.runId)
            assertEquals(40L, closed.tokens)
            assertNotNull(closed.endedAt)
            assertThrows(IllegalArgumentException::class.java) {
                storage.goalRuns.checkpointUsage(stale, 3, 4, 50, 600)
            }
            assertEquals(closed, storage.goalRuns.resolve(started.runId))
        }

    private fun request(
        goalId: String,
        turnId: String,
    ) = GoalTurnStart(
        goalId,
        GoalWakeReason.USER_OPEN,
        TurnStartSpec("session", turnId, "model-$turnId", "snapshot", "run"),
        limits,
    )

    private fun coordinator(storage: HelixStorage) = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-run-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "goal-run-${UUID.randomUUID()}")
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
