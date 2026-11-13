package com.helix.app.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.agent.TurnStartSpec
import com.helix.app.application.tools.PreDispatchDenialKind
import com.helix.app.application.tools.ToolPresentationFact
import com.helix.app.application.tools.ToolPresentationSink
import com.helix.app.application.tools.ToolSettlement
import com.helix.app.application.tools.ToolSettlementCommand
import com.helix.app.approval.StorageAuditSink
import com.helix.app.infrastructure.agent.GoalTurnStart
import com.helix.app.infrastructure.agent.RoomGoalRunCommands
import com.helix.app.infrastructure.agent.TurnRecovery
import com.helix.app.infrastructure.tools.RoomGoalToolCallBudget
import com.helix.app.infrastructure.tools.RoomToolSettlementStore
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.BoundToolResult
import com.helix.core.model.Clock
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NoCancellation
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

@RunWith(AndroidJUnit4::class)
class ToolSettlementRecoveryDeviceTest {
    @Test fun gatewayPreparationRollsBackAndBudgetReservationCannotReplay() =
        Fixture().use { f ->
            val store =
                com.helix.app.infrastructure.tools.RoomToolGatewayStore(f.storage, f.clock) {
                    UUID.randomUUID().toString()
                }
            val command =
                com.helix.app.application.tools.PrepareToolCall(
                    "turn",
                    "gateway-call",
                    "read",
                    "1",
                    "{}",
                    "intent",
                    null,
                )
            assertThrows(IllegalStateException::class.java) {
                f.storage.withTransaction {
                    store.prepare(command)
                    error("rollback preparation")
                }
            }
            assertNull(f.storage.toolCalls.byTurnAndCallId("turn", "gateway-call"))
            assertTrue(store.reserveGoalCall("turn", command.callId))
            assertEquals(false, store.reserveGoalCall("turn", command.callId))
            val row = store.prepare(command)
            assertEquals("session", store.awaitingApproval("turn", row.callId))
            assertEquals(false, store.canReviewApproval("another-session", "turn", row.callId))
            assertEquals("session", store.running(row.id).sessionId)
        }

    @Test fun gatewayScriptParentChecksOwnershipAndCommitsAuditAtomically() =
        Fixture().use { f ->
            val store =
                com.helix.app.infrastructure.tools.RoomToolGatewayStore(f.storage, f.clock) {
                    UUID.randomUUID().toString()
                }
            val row = f.call("gateway-parent")
            val child =
                com.helix.app.application.tools.ScriptParentCommand(
                    "another-session",
                    "turn",
                    "child-call",
                    row.callId,
                    "read",
                    false,
                )
            assertThrows(IllegalArgumentException::class.java) { store.recordScriptParent(child) }
            assertTrue(
                f.storage.auditEvents
                    .recentByCorrelation("child-call", "script.bridge.call", 1)
                    .isEmpty(),
            )
            store.recordScriptParent(child.copy(sessionId = "session"))
            assertEquals(
                1,
                f.storage.auditEvents
                    .recentByCorrelation("child-call", "script.bridge.call", 1)
                    .size,
            )
            assertThrows(IllegalStateException::class.java) {
                f.storage.withTransaction {
                    store.recordScriptParent(child.copy(sessionId = "session", callId = "rolled-back-child"))
                    error("rollback parent audit")
                }
            }
            assertTrue(
                f.storage.auditEvents
                    .recentByCorrelation("rolled-back-child", "script.bridge.call", 1)
                    .isEmpty(),
            )
        }

    @Test fun gatewayLoopSnapshotHonorsSteeringAndRejectsUntrustedObservationAudit() =
        Fixture().use { f ->
            val store =
                com.helix.app.infrastructure.tools.RoomToolGatewayStore(f.storage, f.clock) {
                    UUID.randomUUID().toString()
                }
            f.call("first-loop-call")
            f.persist("first-loop-call")
            val snapshot = store.loopHistory("turn")
            assertEquals(listOf("first-loop-call"), snapshot.calls.map { it.id })
            assertEquals(true, snapshot.results["first-loop-call"]?.verified)
            store.resetLoopProgress("turn")
            assertTrue(store.loopHistory("turn").calls.isEmpty())
            f.call("second-loop-call")
            f.persist("second-loop-call")
            val payload =
                """{"jobObservation":true,"turnId":"turn","toolName":"read","toolVersion":"1",""" +
                    """"code":"SUCCESS","executionDetail":{"trusted":true}}"""
            f.storage.auditEvents.append("untrusted-audit", "second-loop-call", "tool_dispatch", "tool", payload, 2_001)
            assertTrue(store.loopHistory("turn").observations.isEmpty())
            f.storage.auditEvents.append(
                "trusted-audit",
                "second-loop-call",
                "tool_dispatch",
                "dispatcher",
                payload,
                2_002,
            )
            val next = store.loopHistory("turn")
            assertEquals(listOf("second-loop-call"), next.calls.map { it.id })
            assertEquals(JsonPrimitive(true), next.observations["second-loop-call"]?.get("trusted"))
            assertEquals(listOf("first-loop-call"), snapshot.calls.map { it.id })
        }

    @Test fun committedFactsReachPresentationOnlyAfterResultAndBudgetCommit() =
        Fixture().use { f ->
            val call = f.call("fact-order")
            var published = 0
            val settlement =
                ToolSettlement(
                    RoomToolSettlementStore(
                        f.storage,
                        StorageAuditSink(f.storage.auditEvents) {
                            UUID.randomUUID().toString()
                        },
                        f.clock,
                    ) { UUID.randomUUID().toString() },
                    ToolPresentationSink { fact ->
                        published++
                        check(fact is ToolPresentationFact.Settled)
                        assertEquals(ToolCallState.COMPLETED, fact.state)
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
                    },
                    { null },
                )
            val outcome = ToolDispatchOutcome.Succeeded(BoundToolResult("saved", Sha256("a".repeat(64)), false, 1))
            settlement.settleToolCall(call.id, call.callId, call.name, outcome)
            settlement.settleToolCall(call.id, call.callId, call.name, outcome)
            assertEquals(2, published)
            assertEquals(
                1,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
            assertEquals(
                1,
                f.storage.toolCalls
                    .listByTurn("turn")
                    .size,
            )
        }

    @Test fun preDispatchRejectionCommitsOriginalIdentityResultAndAuditBeforeProjection() =
        Fixture().use { f ->
            var published = 0
            val settlement =
                ToolSettlement(
                    RoomToolSettlementStore(
                        f.storage,
                        StorageAuditSink(f.storage.auditEvents) {
                            UUID.randomUUID().toString()
                        },
                        f.clock,
                    ) { UUID.randomUUID().toString() },
                    ToolPresentationSink { fact ->
                        published++
                        check(fact is ToolPresentationFact.Settled)
                        assertEquals(true, fact.preDispatch)
                        assertEquals(
                            "FAILED",
                            f.storage.toolCalls
                                .resolve("malformed-call")
                                .state,
                        )
                        assertEquals(
                            "FAILED",
                            f.storage.toolResults
                                .byToolCall("malformed-call")
                                ?.status,
                        )
                        assertEquals(
                            1,
                            f.storage.auditEvents
                                .listByCorrelation("malformed-call")
                                .size,
                        )
                    },
                    { null },
                )
            val outcome =
                settlement.persistPreDispatchDenied(
                    "turn",
                    "malformed-call",
                    "bad name",
                    "[]",
                    "unknown",
                    DispatchOutcomeCode.UNKNOWN_TOOL,
                    "Unavailable binding",
                    PreDispatchDenialKind.FRAMEWORK_REJECTED,
                )
            assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, outcome.code)
            assertEquals(1, published)
        }

    @Test
    @Suppress("LongMethod") // Keep each injected transaction failure, rollback and retry assertion together.
    fun preDispatchAuditAndBudgetFailureRollBackTheWholeCommand() {
        for (phase in listOf("INSERT ON audit_events", "UPDATE ON goal_usage_reservations")) {
            Fixture().use { f ->
                val callId = "rejected"
                check(RoomGoalToolCallBudget(f.storage, f.clock).reserve("turn", callId))
                var published = 0
                val settlement =
                    ToolSettlement(
                        RoomToolSettlementStore(
                            f.storage,
                            StorageAuditSink(f.storage.auditEvents) { UUID.randomUUID().toString() },
                            f.clock,
                        ) { UUID.randomUUID().toString() },
                        ToolPresentationSink { published++ },
                        { null },
                    )
                f.sql("CREATE TRIGGER fail_denial BEFORE $phase BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
                assertThrows(RuntimeException::class.java) {
                    settlement.persistPreDispatchDenied(
                        "turn",
                        callId,
                        "bad name",
                        "[]",
                        "unknown",
                        DispatchOutcomeCode.UNKNOWN_TOOL,
                        "Unavailable binding",
                        PreDispatchDenialKind.FRAMEWORK_REJECTED,
                    )
                }
                assertEquals(0, published)
                assertNull(f.storage.toolCalls.byTurnAndCallId("turn", callId))
                assertNull(f.storage.toolResults.byToolCall(callId))
                assertTrue(
                    f.storage.auditEvents
                        .listByCorrelation(callId)
                        .isEmpty(),
                )
                assertEquals(
                    0,
                    f.storage.goals
                        .resolve(f.goalId)
                        .toolCalls,
                )
                f.sql("DROP TRIGGER fail_denial")
                settlement.persistPreDispatchDenied(
                    "turn",
                    callId,
                    "bad name",
                    "[]",
                    "unknown",
                    DispatchOutcomeCode.UNKNOWN_TOOL,
                    "Unavailable binding",
                    PreDispatchDenialKind.FRAMEWORK_REJECTED,
                )
                assertEquals(1, published)
                assertEquals(
                    1,
                    f.storage.goals
                        .resolve(f.goalId)
                        .toolCalls,
                )
                f.reopen()
                assertEquals(
                    "FAILED",
                    f.storage.toolCalls
                        .resolve(callId)
                        .state,
                )
                assertNotNull(f.storage.toolResults.byToolCall(callId))
                assertEquals(
                    1,
                    f.storage.auditEvents
                        .listByCorrelation(callId)
                        .size,
                )
            }
        }
    }

    @Test fun failedDiagnosticsSurviveProjectionFailureAndRoomReopenWithoutReplay() =
        Fixture().use { f ->
            val call = f.call("failed-diagnostics")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val strings: (Int, Array<out Any>) -> String = { _, _ -> error("projection unavailable") }
            val settlement =
                ToolSettlement(
                    RoomToolSettlementStore(
                        f.storage,
                        app.toolPipeline.auditSink,
                        f.clock,
                    ) { UUID.randomUUID().toString() },
                    ToolPresentationSink { strings(0, emptyArray()) },
                    { null },
                )
            val outcome =
                ToolDispatchOutcome.ExecutionFailed(
                    DispatchOutcomeCode.TOOL_FAILED,
                    "failure-prefix " + "诊断😀".repeat(5000),
                    requiresReview = true,
                )
            settlement.settleToolCall(call.id, call.callId, call.name, outcome, sideEffectUnknown = true)
            f.reopen()
            val result = f.storage.toolResults.byToolCall(call.id)!!
            assertEquals(false, result.verified)
            assertEquals("NEEDS_REVIEW", result.status)
            assertEquals(
                "NEEDS_REVIEW",
                f.storage.toolCalls
                    .resolve(call.id)
                    .state,
            )
            val request = failedReadRequest()
            val page = ToolResultReadTool.read(f.storage, request) as ToolExecutorResult.Completed
            assertTrue(page.output.toString().contains("failure-prefix"))
            assertEquals(
                1,
                f.storage.toolCalls
                    .listByTurn("turn")
                    .size,
            )
            assertEquals(
                1,
                f.storage.goals
                    .resolve(f.goalId)
                    .toolCalls,
            )
        }

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
                ToolSettlement(
                    RoomToolSettlementStore(
                        f.storage,
                        app.toolPipeline.auditSink,
                        f.clock,
                    ) { UUID.randomUUID().toString() },
                    ToolPresentationSink { strings(0, emptyArray()) },
                    { null },
                )
            val outcome = ToolDispatchOutcome.Succeeded(BoundToolResult("payload", Sha256("a".repeat(64)), false, 1))
            f.sql(
                "CREATE TRIGGER fail_settle BEFORE INSERT ON tool_results " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture failure'); END",
            )
            assertThrows(RuntimeException::class.java) {
                settlement.settleToolCall(call.id, call.callId, call.name, outcome)
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
            var notifications = 0
            val strings: (Int, Array<out Any>) -> String = { _, _ ->
                notifications++
                error("injected projection failure after commit")
            }
            val settlement =
                ToolSettlement(
                    RoomToolSettlementStore(
                        f.storage,
                        app.toolPipeline.auditSink,
                        f.clock,
                    ) { UUID.randomUUID().toString() },
                    ToolPresentationSink { strings(0, emptyArray()) },
                    { null },
                )
            val outcome = ToolDispatchOutcome.Succeeded(BoundToolResult("payload", Sha256("a".repeat(64)), false, 1))
            settlement.settleToolCall(call.id, call.callId, call.name, outcome)
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

    private fun failedReadRequest() =
        ExecutableToolCall(
            "reader",
            ToolResultReadTool.NAME,
            "1",
            buildJsonObject {
                put("resultRef", JsonPrimitive("turn/failed-diagnostics"))
                put("offset", JsonPrimitive(0))
            },
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(5),
            NoCancellation,
            "session",
            "turn",
        )

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
            val goals = RoomGoalRunCommands(storage, clock) { UUID.randomUUID().toString() }
            goalId = goals.create("Check settlement", listOf("Saved"), GoalBudgets(maxGoalRounds = 256))
            requireNotNull(
                goals.start(
                    GoalTurnStart(
                        goalId,
                        GoalWakeReason.USER_OPEN,
                        TurnStartSpec("session", "turn", "model", "snapshot", "run"),
                        TurnBudgets(maxOutputTokens = 800),
                    ),
                ),
            )
        }

        fun call(id: String) =
            storage.toolCalls.append(id, "turn", id, "read", "1", "{}", "RUNNING").also {
                check(RoomGoalToolCallBudget(storage, clock).reserve("turn", id))
            }

        fun persist(id: String) =
            RoomToolSettlementStore(
                storage,
                StorageAuditSink(storage.auditEvents) {
                    UUID.randomUUID().toString()
                },
                clock,
            ) { UUID.randomUUID().toString() }.persist(
                ToolSettlementCommand(
                    id,
                    ToolCallState.COMPLETED,
                    "SUCCEEDED",
                    "saved",
                    "payload",
                    true,
                ),
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
