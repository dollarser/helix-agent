package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.StoredGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalTurnBindingDeviceTest {
    @Test
    fun coordinatorBindingSurvivesDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-turn-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "goal-turn-${UUID.randomUUID()}")
        try {
            val first = HelixStorage.open(context, name, content)
            try {
                seed(first)
                start(first, "session", "turn")
                assertEquals("run", first.goalTurnBindings.byTurn("turn")?.runId)
            } finally {
                first.close()
            }
            val reopened = HelixStorage.open(context, name, content)
            try {
                assertEquals("run", reopened.goalTurnBindings.byTurn("turn")?.runId)
                assertEquals("goal", reopened.goalRuns.resolve("run").goalId)
                assertEquals("session", reopened.turns.resolve("turn").sessionId)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    @Test
    fun crossSessionAndClosedRunRejectionRollBackTurnCreation() =
        withStorage { storage ->
            seed(storage)
            start(storage, "session", "turn")
            storage.sessions.create("other", "Other", null, null, 1_000)
            assertThrows(IllegalArgumentException::class.java) { start(storage, "other", "cross") }
            assertThrows(IllegalArgumentException::class.java) { storage.turns.resolve("cross") }
            assertTrue(storage.messages.listBySession("other").isEmpty())
            val run = storage.goalRuns.resolve("run")
            storage.goalRuns.finish(run, "RUN_FINISHED", 2_000, 0, 0, 0, 0)
            assertThrows(IllegalArgumentException::class.java) { start(storage, "session", "closed") }
            assertThrows(IllegalArgumentException::class.java) { storage.turns.resolve("closed") }
            assertEquals("run", storage.goalTurnBindings.byTurn("turn")?.runId)
        }

    @Test
    fun missingRunRollsBackAndOrdinaryTurnStaysUnbound() =
        withStorage { storage ->
            seed(storage)
            assertThrows(IllegalArgumentException::class.java) { start(storage, "session", "missing", "absent") }
            assertThrows(IllegalArgumentException::class.java) { storage.turns.resolve("missing") }
            val ordinary = start(storage, "session", "ordinary", null)
            assertNull(storage.goalTurnBindings.byTurn("ordinary"))
            assertEquals(TurnState.WAITING_MODEL, ordinary.snapshot().phase)
        }

    private fun start(
        storage: HelixStorage,
        session: String,
        turn: String,
        run: String? = "run",
    ) = TurnCoordinator.start(
        storage,
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(2_000)
        },
        { UUID.randomUUID().toString() },
        TurnStartSpec(session, turn, "model-$turn", "snapshot", "hello", goalRunId = run),
    )

    private fun seed(storage: HelixStorage) {
        storage.sessions.create("session", "Goal", null, null, 1_000)
        storage.goals.save(
            StoredGoal(
                id = "goal",
                objective = "Verify durable association",
                criteria = emptyList(),
                budgets = GoalBudgets(10, 20, 100_000, 600_000, 60_000, 0),
                state = GoalState.RUNNING.name,
                planId = null,
                planHash = null,
                nextCheckpoint = null,
                correlationId = "corr-goal",
                runCount = 1,
                modelCalls = 0,
                toolCalls = 0,
                totalTokens = 0,
                runTimeMillis = 0,
                currentWakeMillis = 0,
                retries = 0,
                lastWakeReason = "USER_OPEN",
                error = null,
                finishReason = null,
            ),
        )
        storage.goalRuns.open("run", "goal", "USER_OPEN", 1_000)
    }

    private fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-binding-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, "binding-${UUID.randomUUID()}")
        val storage = HelixStorage.open(context, name, content)
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }
}
