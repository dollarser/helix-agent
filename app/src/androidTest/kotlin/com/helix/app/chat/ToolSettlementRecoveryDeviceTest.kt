package com.helix.app.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.agent.TurnStartSpec
import com.helix.app.engine.TurnRecovery
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.BoundToolResult
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ToolSettlementRecoveryDeviceTest {
    @Test
    fun failedCommitStillPropagatesBeforeAnyProjection() =
        Fixture().use { f ->
            val call = f.call("failed-commit")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            var notifications = 0
            val strings: (Int, Array<out Any>) -> String = { _, _ ->
                notifications++
                "projection"
            }
            val settlement =
                ChatToolSettlement(
                    f.storage,
                    app.toolPipeline,
                    f.clock,
                    { UUID.randomUUID().toString() },
                    strings,
                    ChatToolTimeline(MutableStateFlow(app.chatService.screen.value), strings),
                    { null },
                )
            val outcome = ToolDispatchOutcome.Succeeded(BoundToolResult("payload", Sha256("a".repeat(64)), false, 1))
            f.sql(
                "CREATE TRIGGER fail_settle BEFORE INSERT ON tool_results " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END",
            )
            assertThrows(RuntimeException::class.java) {
                settlement.settleToolCall(call, call.callId, call.name, outcome)
            }
            assertEquals(0, notifications)
            assertEquals(
                "RUNNING",
                f.storage.toolCalls
                    .resolve(call.id)
                    .state,
            )
            assertNull(f.storage.toolResults.byToolCall(call.id))
            assertEquals(
                0,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
        }

    @Test
    fun committedResultSurvivesProjectionFailureAndRepeatedRecovery() =
        Fixture().use { f ->
            val call = f.call("committed")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val screen = MutableStateFlow(app.chatService.screen.value)
            var notifications = 0
            val strings: (Int, Array<out Any>) -> String = { _, _ ->
                notifications++
                error("injected projection failure after commit")
            }
            val settlement =
                ChatToolSettlement(
                    f.storage,
                    app.toolPipeline,
                    f.clock,
                    { UUID.randomUUID().toString() },
                    strings,
                    ChatToolTimeline(screen, strings),
                    { null },
                )
            val outcome = ToolDispatchOutcome.Succeeded(BoundToolResult("payload", Sha256("a".repeat(64)), false, 1))
            settlement.settleToolCall(call, call.callId, call.name, outcome)
            assertEquals(1, notifications)
            val saved = f.storage.toolResults.byToolCall(call.id)
            assertNotNull(saved)
            f.reopen()
            repeat(2) { TurnRecovery(f.storage, f.clock).recover() }
            assertEquals(
                "COMPLETED",
                f.storage.toolCalls
                    .resolve(call.id)
                    .state,
            )
            assertEquals(saved, f.storage.toolResults.byToolCall(call.id))
            assertEquals(
                1,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
        }

    @Test
    fun effectBeforeFailedCommitRemainsUncertainAfterRepeatedRecovery() =
        Fixture().use { f ->
            f.call("effect")
            val effect = File(f.context.cacheDir, "${f.name}-effect")
            try {
                effect.appendText("executed once\n")
                f.sql(
                    "CREATE TRIGGER fail_settle BEFORE INSERT ON tool_results " +
                        "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END",
                )
                assertThrows(RuntimeException::class.java) { f.persist("effect") }
                f.sql("DROP TRIGGER fail_settle")
                f.reopen()
                repeat(2) { TurnRecovery(f.storage, f.clock).recover() }
                assertEquals(
                    "INTERRUPTED",
                    f.storage.toolCalls
                        .resolve("effect")
                        .state,
                )
                assertNull(f.storage.toolResults.byToolCall("effect"))
                assertEquals(listOf("executed once"), effect.readLines())
                assertEquals(
                    "INTERRUPTED",
                    f.storage.turns
                        .resolve("turn")
                        .state,
                )
            } finally {
                effect.delete()
            }
        }

    @Test
    fun failedStartCommitLeavesPendingCallCancelledAfterRecovery() =
        Fixture().use { f ->
            val call = f.call("not-started")
            f.storage.toolCalls.updateState(call, ToolCallState.PENDING)
            f.sql(
                "CREATE TRIGGER fail_start BEFORE UPDATE OF state ON tool_calls " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END",
            )
            assertThrows(RuntimeException::class.java) { f.storage.toolCalls.updateState(call, ToolCallState.RUNNING) }
            f.sql("DROP TRIGGER fail_start")
            f.reopen()
            repeat(2) { TurnRecovery(f.storage, f.clock).recover() }
            assertEquals(
                "CANCELLED",
                f.storage.toolCalls
                    .resolve(call.id)
                    .state,
            )
            assertEquals(
                "CANCELLED",
                f.storage.toolResults
                    .byToolCall(call.id)
                    ?.status,
            )
            assertEquals(
                1,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
        }

    @Test
    fun bodyWriteFailureLeavesCallAndBudgetUnsettled() =
        Fixture().use { f ->
            f.call("body")
            val backup = File(f.content.parentFile, "${f.content.name}-backup")
            check(f.content.renameTo(backup))
            try {
                f.content.writeText("not a directory")
                assertThrows(Exception::class.java) { f.persist("body") }
                assertEquals(
                    "RUNNING",
                    f.storage.toolCalls
                        .resolve("body")
                        .state,
                )
                assertNull(f.storage.toolResults.byToolCall("body"))
                assertEquals(
                    0,
                    f.storage.goals
                        .resolve(f.goalId)
                        .toolCalls,
                )
            } finally {
                f.content.delete()
                check(backup.renameTo(f.content))
            }
            f.persist("body")
            assertEquals(
                1,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
        }

    @Test
    fun resultVerificationStateAndBudgetFailuresRollBackTogether() {
        for (phase in listOf(
            "INSERT ON tool_results",
            "UPDATE OF verified ON tool_results",
            "UPDATE OF state ON tool_calls",
            "UPDATE ON goal_usage_reservations",
        )) {
            Fixture().use { f ->
                val call = f.call("first")
                f.sql("CREATE TRIGGER fail_settle BEFORE $phase BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
                assertThrows(RuntimeException::class.java) { f.persist(call.id) }
                assertEquals(
                    "RUNNING",
                    f.storage.toolCalls
                        .resolve(call.id)
                        .state,
                )
                assertNull(f.storage.toolResults.byToolCall(call.id))
                assertEquals(
                    0,
                    f.storage.goals
                        .resolve(f.goalId)
                        .toolCalls,
                )
                f.sql("DROP TRIGGER fail_settle")
                f.persist(call.id)
                f.persist(call.id)
                f.reopen()
                assertEquals(
                    "COMPLETED",
                    f.storage.toolCalls
                        .resolve(call.id)
                        .state,
                )
                assertEquals(
                    true,
                    f.storage.toolResults
                        .byToolCall(call.id)
                        ?.verified,
                )
                assertEquals(
                    1,
                    f.storage.goals
                        .resolve(f.goalId)
                        .toolCalls,
                )
            }
        }
    }

    @Test
    fun terminalParentDoesNotHideUnsettledChildrenAfterReopen() =
        Fixture().use { f ->
            f.call("lost")
            val queued = f.call("queued")
            f.storage.toolCalls.updateState(queued, ToolCallState.PENDING)
            f.call("saved")
            f.persist("saved")
            val turn = f.storage.turns.resolve("turn")
            f.storage.turns.updateState(turn, TurnState.FAILED, turn.stepCount, 2_000, "SETTLEMENT_FAILED")
            f.reopen()
            TurnRecovery(f.storage, f.clock).recover()
            assertEquals(
                "NEEDS_REVIEW",
                f.storage.toolCalls
                    .resolve("lost")
                    .state,
            )
            assertEquals(
                "COMPLETED",
                f.storage.toolCalls
                    .resolve("saved")
                    .state,
            )
            assertNotNull(f.storage.toolResults.byToolCall("saved"))
            assertEquals(
                "CANCELLED",
                f.storage.toolCalls
                    .resolve("queued")
                    .state,
            )
            assertEquals(
                "CANCELLED",
                f.storage.toolResults
                    .byToolCall("queued")
                    ?.status,
            )
            assertEquals(
                3,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
            TurnRecovery(f.storage, f.clock).recover()
            assertEquals(
                3,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
        }

    private class Fixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "settlement-${UUID.randomUUID()}.db"
        val content = File(context.cacheDir, name)
        var storage = HelixStorage.open(context, name, content)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2_000)
            }
        val goalId: String

        init {
            storage.sessions.create("session", "Settlement", null, null, 1_000)
            val goals = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
            goalId = goals.create("Check settlement", listOf("Saved"), GoalBudgets(10, 20, 100_000, 600_000, 60_000, 0))
            requireNotNull(
                goals.start(
                    GoalTurnStart(
                        goalId,
                        GoalWakeReason.USER_OPEN,
                        TurnStartSpec("session", "turn", "model", "snapshot", "run"),
                        TurnBudgets(5, 10, 800, 800, 5_000),
                    ),
                ),
            )
        }

        fun call(id: String) =
            storage.toolCalls.append(id, "turn", id, "read", "1", "{}", "RUNNING").also {
                check(GoalToolCallBudget(storage, clock).reserve("turn", id))
            }

        fun persist(id: String) =
            ToolSettlementWriter(storage, clock) { UUID.randomUUID().toString() }.persist(
                storage.toolCalls.resolve(id),
                ToolCallState.COMPLETED,
                "SUCCEEDED",
                "saved",
                "payload",
                true,
            )

        fun sql(statement: String) =
            SQLiteDatabase
                .openDatabase(
                    context.getDatabasePath(name).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { it.execSQL(statement) }

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
