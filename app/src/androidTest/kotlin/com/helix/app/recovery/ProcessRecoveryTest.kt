package com.helix.app.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.PendingToolCall
import com.helix.core.agent.RecoveryCoordinator
import com.helix.core.agent.ToolOutcome
import com.helix.core.agent.TurnEffect
import com.helix.core.agent.TurnEvent
import com.helix.core.agent.TurnReducer
import com.helix.core.model.Clock
import com.helix.core.model.CorrelationId
import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalState
import com.helix.core.model.SessionId
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.StoredGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import com.helix.core.agent.TurnState as RuntimeTurnState
import com.helix.core.model.TurnState as Phase

/**
 * Process-restart recovery fixture (verification matrix HXA-015, "进程恢复 fixture").
 *
 * Process death is simulated the way it can be in one process: the "dying" storage instance
 * commits its rows and is closed — a killed process can only leave committed state behind,
 * and closing the connection leaves exactly that on disk. Recovery then runs on a fresh
 * [HelixStorage] over the same database file, as the next process start would.
 *
 * The reducer-level wake/resume semantics are covered by `:core:agent:test`; this fixture
 * proves the persisted outcome of the coordinator's transactional pairing (doc 9.2).
 */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryTest {
    @Test
    fun processDeathParksInterruptedTurnAndRunningGoal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dying = isolatedStorage(context, "death-1")
        try {
            seedPreDeathState(dying)
        } finally {
            dying.close()
        }

        // A fresh process opens the same file and recovers.
        val storage = isolatedStorage(context, "death-1")
        val report = RecoveryCoordinatorApp(storage, FixedClock(2_000L)).recover()

        // --- the report: exactly the leftover work, nothing else
        assertEquals(mapOf("turn-1" to "call-1", "turn-2" to null), report.interruptedTurns)
        assertEquals(mapOf("turn-1" to listOf("call-1", "call-2")), report.parkedToolCalls)
        assertEquals(listOf("goal-1"), report.parkedGoals)
        assertEquals(listOf("run-1"), report.closedRuns)

        assertTurnsRecovered(storage)
        assertToolCallsParkedCorrectly(storage)
        assertGoalParkedAndRunClosed(storage)
        assertRecoveryAuditEvents(storage)

        // --- idempotent: the next start finds nothing left to recover and writes nothing.
        val again = RecoveryCoordinatorApp(storage, FixedClock(2_000L)).recover()
        assertTrue(again.interruptedTurns.isEmpty())
        assertTrue(again.parkedToolCalls.isEmpty())
        assertTrue(again.parkedGoals.isEmpty())
        assertTrue(again.closedRuns.isEmpty())
        assertEquals(2, storage.auditEvents.listByCorrelation("session-1").size)
        assertEquals(2, storage.auditEvents.listByCorrelation("corr-goal-1").size)
    }

    @Test
    fun wakeGateFollowsRecoveryAndTheDeadRunIsNeverReplayed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dying = isolatedStorage(context, "death-2")
        try {
            dying.sessions.create("session-1", "Goal only", null, null, 1_000L)
            dying.goals.save(goal("goal-1", GoalState.RUNNING.name, currentWakeMillis = 1_300L))
            dying.goalRuns.open("run-1", "goal-1", GoalWakeReason.USER_OPEN.name, 1_300L)
        } finally {
            dying.close()
        }

        val storage = isolatedStorage(context, "death-2")
        // While the goal is still RUNNING, a wake (e.g. a queued notification) is dropped.
        assertFalse(RecoveryCoordinator.wakeAllowed(GoalState.RUNNING))

        val report = RecoveryCoordinatorApp(storage, FixedClock(2_000L)).recover()
        assertEquals(listOf("goal-1"), report.parkedGoals)
        assertEquals(listOf("run-1"), report.closedRuns)

        // After the park the same wake source is legitimate again (ADR-0004: NOTIFICATION_ACTION
        // wakes pass the state gate).
        assertEquals(GoalState.PAUSED.name, storage.goals.resolve("goal-1").state)
        assertTrue(RecoveryCoordinator.wakeAllowed(GoalState.valueOf(storage.goals.resolve("goal-1").state)))

        // An explicit continue starts a fresh run; the dead run stays closed — it is never
        // re-opened or replayed (the domain-side Continued -> StartRun gate is covered in
        // :core:agent:test).
        storage.goalRuns.open("run-2", "goal-1", GoalWakeReason.NOTIFICATION_ACTION.name, 3_000L)
        assertEquals(2, storage.goalRuns.listByGoal("goal-1").size)
        assertEquals("NOTIFICATION_ACTION", storage.goalRuns.resolve("run-2").wakeReason)
        val deadRun = storage.goalRuns.resolve("run-1")
        assertEquals("INTERRUPTED", deadRun.outcome)
        assertEquals(2_000L, deadRun.endedAt)
    }

    @Test
    fun clockRewindBeforeStartTimesIsClampedNotReversed() {
        // A backward wall-clock step between the dead process and this start (manual time
        // change, NTP correction) puts `now` before the persisted startedAt: recovery must
        // still succeed, with endedAt clamped to startedAt (no reversed timeline, no negative
        // wake duration).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dying = isolatedStorage(context, "rewind-1")
        try {
            dying.sessions.create("session-1", "Rewind", null, null, 1_000L)
            val turn = dying.turns.start("turn-1", "session-1", 1_100L)
            dying.turns.updateState(turn, Phase.RUNNING_TOOL, 1, null, null)
            dying.seedCall("tc-1", "turn-1", "call-1", "bash", """{"cmd":"ls"}""", ToolCallState.RUNNING)
            dying.goals.save(goal("goal-1", GoalState.RUNNING.name, currentWakeMillis = 200L))
            dying.goalRuns.open("run-1", "goal-1", GoalWakeReason.USER_OPEN.name, 1_300L)
        } finally {
            dying.close()
        }

        val storage = isolatedStorage(context, "rewind-1")
        val report = RecoveryCoordinatorApp(storage, FixedClock(500L)).recover()
        assertEquals(mapOf("turn-1" to "call-1"), report.interruptedTurns)
        assertEquals(listOf("goal-1"), report.parkedGoals)
        assertEquals(listOf("run-1"), report.closedRuns)

        val recovered = storage.turns.resolve("turn-1")
        assertEquals(Phase.INTERRUPTED.name, recovered.state)
        assertEquals(1_100L, recovered.endedAt)
        val run = storage.goalRuns.resolve("run-1")
        assertEquals("INTERRUPTED", run.outcome)
        assertEquals(1_300L, run.endedAt)
        assertEquals(0L, run.wakeDurationMillis)
        // The goal parked with the wake dropped (same as any process death).
        assertEquals(GoalState.PAUSED.name, storage.goals.resolve("goal-1").state)
        assertEquals(0L, storage.goals.resolve("goal-1").currentWakeMillis)
    }

    @Test
    fun recoveredTurnWithoutSideEffectResumesDirectlyWithoutReplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dying = isolatedStorage(context, "death-3")
        try {
            dying.sessions.create("session-1", "Waiting approval", null, null, 1_000L)
            val turn = dying.turns.start("turn-1", "session-1", 1_100L)
            dying.turns.updateState(turn, Phase.WAITING_APPROVAL, 1, null, null)
            dying.seedCall(
                "tc-1",
                "turn-1",
                "call-3",
                "write",
                """{"path":"/tmp/x"}""",
                ToolCallState.AWAITING_APPROVAL,
            )
        } finally {
            dying.close()
        }

        val storage = isolatedStorage(context, "death-3")
        val report = RecoveryCoordinatorApp(storage, FixedClock(2_000L)).recover()
        assertEquals(mapOf("turn-1" to null), report.interruptedTurns)
        assertTrue(report.parkedToolCalls.isEmpty())
        // The call never executed, so it is not uncertain and keeps its durable state.
        assertEquals(
            ToolCallState.AWAITING_APPROVAL.name,
            storage.toolCalls.byTurnAndCallId("turn-1", "call-3")?.state,
        )

        // The runtime state reconstructed from the persisted facts is immediately resumable:
        // no side-effect review is needed when nothing was in flight.
        val recovered =
            RuntimeTurnState(
                sessionId = SessionId("session-1"),
                turnId = TurnId("turn-1"),
                correlationId = CorrelationId("corr-turn-1"),
                phase = Phase.INTERRUPTED,
                budgets = TurnBudgets(8, 4, 12_000L, 2_000L, 14_000L),
                pendingCalls =
                    listOf(
                        PendingToolCall(
                            ToolCallId("call-3"),
                            ToolName("write"),
                            ToolVersion(1),
                            true,
                            null,
                            ToolCallState.AWAITING_APPROVAL,
                        ),
                    ),
            )
        assertTrue(RecoveryCoordinator.canResumeTurn(recovered.phase, hasUncertainToolCall = false))
        val resumed = TurnReducer.reduce(recovered, TurnEvent.Lifecycle.TurnResumed)
        assertFalse(resumed.ignored)
        assertEquals(Phase.BUILDING_CONTEXT, resumed.state.phase)
        // The unexecuted call is recorded as failed-interrupted (the provider conversation
        // still receives a result for it) — it is never re-executed: the only effect is
        // context building, no tool or model work.
        assertEquals(1, resumed.state.recordedOutcomes.size)
        val failed =
            resumed.state.recordedOutcomes
                .single()
                .outcome as ToolOutcome.Failed
        assertEquals(ErrorCode.INTERRUPTED, failed.error.code)
        assertEquals(listOf<TurnEffect>(TurnEffect.BuildContext), resumed.effects)
    }

    // ------------------------------------------------------------------ helpers

    private fun assertTurnsRecovered(storage: HelixStorage) {
        val t1 = storage.turns.resolve("turn-1")
        assertEquals(Phase.INTERRUPTED.name, t1.state)
        assertEquals(2_000L, t1.endedAt)
        assertNull(t1.errorCode)
        val t2 = storage.turns.resolve("turn-2")
        assertEquals(Phase.INTERRUPTED.name, t2.state)
        assertEquals(2_000L, t2.endedAt)
        // The terminal turn is untouched.
        val t3 = storage.turns.resolve("turn-3")
        assertEquals(Phase.COMPLETED.name, t3.state)
        assertEquals(1_500L, t3.endedAt)
    }

    private fun assertToolCallsParkedCorrectly(storage: HelixStorage) {
        // In-flight calls parked; the awaiting-approval call never executed and is untouched;
        // the completed call is untouched. Nothing was re-executed.
        assertEquals(
            ToolCallState.INTERRUPTED.name,
            storage.toolCalls.byTurnAndCallId("turn-1", "call-1")?.state,
        )
        assertEquals(
            ToolCallState.INTERRUPTED.name,
            storage.toolCalls.byTurnAndCallId("turn-1", "call-2")?.state,
        )
        assertEquals(
            ToolCallState.AWAITING_APPROVAL.name,
            storage.toolCalls.byTurnAndCallId("turn-2", "call-3")?.state,
        )
        assertEquals(
            ToolCallState.COMPLETED.name,
            storage.toolCalls.byTurnAndCallId("turn-3", "call-4")?.state,
        )
    }

    private fun assertGoalParkedAndRunClosed(storage: HelixStorage) {
        // RUNNING parks in PAUSED with the checkpoint kept; PAUSED stays.
        val g1 = storage.goals.resolve("goal-1")
        assertEquals(GoalState.PAUSED.name, g1.state)
        assertEquals(9_999L, g1.nextCheckpoint)
        assertEquals(0L, g1.currentWakeMillis)
        assertEquals(GoalState.PAUSED.name, storage.goals.resolve("goal-2").state)

        // The open run is closed as INTERRUPTED with the usage it had persisted.
        val run1 = storage.goalRuns.resolve("run-1")
        assertEquals("INTERRUPTED", run1.outcome)
        assertEquals(2_000L, run1.endedAt)
        assertEquals(700L, run1.wakeDurationMillis)
        assertEquals(0, run1.modelCalls)
        assertEquals(0, run1.toolCalls)
        assertEquals(0L, run1.tokens)
    }

    private fun assertRecoveryAuditEvents(storage: HelixStorage) {
        // Every state change has its event in the same committed state (doc 9.2).
        val s1Audit = storage.auditEvents.listByCorrelation("session-1")
        assertEquals(2, s1Audit.size)
        val turnEvent = s1Audit.single { it.type == "recovery.turn_interrupted" }
        assertTrue(turnEvent.redactedPayload.contains("\"turn\":\"turn-1\""))
        assertTrue(turnEvent.redactedPayload.contains("\"uncertainToolCall\":\"call-1\""))
        val parkedEvent = s1Audit.single { it.type == "recovery.tool_calls_parked" }
        assertTrue(parkedEvent.redactedPayload.contains("\"call-1\""))
        assertTrue(parkedEvent.redactedPayload.contains("\"call-2\""))
        assertEquals(1, storage.auditEvents.listByCorrelation("session-2").size)
        assertTrue(
            storage.auditEvents
                .listByCorrelation("session-2")
                .single()
                .redactedPayload
                .contains("\"uncertainToolCall\":null"),
        )
        val g1Audit = storage.auditEvents.listByCorrelation("corr-goal-1")
        assertEquals(2, g1Audit.size)
        assertTrue(g1Audit.any { it.type == "recovery.goal_parked" })
        val runClosed = g1Audit.single { it.type == "recovery.run_closed" }
        assertTrue(runClosed.redactedPayload.contains("\"outcome\":\"INTERRUPTED\""))
        assertEquals(0, storage.auditEvents.listByCorrelation("session-3").size)
    }

    private fun seedPreDeathState(storage: HelixStorage) {
        storage.sessions.create("session-1", "Running tool", null, null, 900L)
        storage.sessions.create("session-2", "Waiting approval", null, null, 950L)
        storage.sessions.create("session-3", "Done", null, null, 990L)

        val t1 = storage.turns.start("turn-1", "session-1", 1_000L)
        storage.turns.updateState(t1, Phase.RUNNING_TOOL, 2, null, null)
        storage.seedCall("tc-1", "turn-1", "call-1", "bash", """{"cmd":"sleep 5"}""", ToolCallState.RUNNING)
        storage.seedCall("tc-2", "turn-1", "call-2", "write", """{"path":"/tmp/a"}""", ToolCallState.PENDING)

        val t2 = storage.turns.start("turn-2", "session-2", 1_100L)
        storage.turns.updateState(t2, Phase.WAITING_APPROVAL, 1, null, null)
        storage.seedCall("tc-3", "turn-2", "call-3", "write", """{"path":"/tmp/b"}""", ToolCallState.AWAITING_APPROVAL)

        val t3 = storage.turns.start("turn-3", "session-3", 1_200L)
        storage.turns.updateState(t3, Phase.COMPLETED, 3, 1_500L, null)
        storage.seedCall("tc-4", "turn-3", "call-4", "read", """{"path":"/tmp/c"}""", ToolCallState.COMPLETED)

        storage.goals.save(goal("goal-1", GoalState.RUNNING.name, nextCheckpoint = 9_999L, currentWakeMillis = 1_300L))
        storage.goalRuns.open("run-1", "goal-1", GoalWakeReason.USER_OPEN.name, 1_300L)
        storage.goals.save(goal("goal-2", GoalState.PAUSED.name))
    }

    private fun goal(
        id: String,
        state: String,
        nextCheckpoint: Long? = null,
        currentWakeMillis: Long = 0L,
    ) = StoredGoal(
        id = id,
        objective = "Persisted goal $id",
        criteria = emptyList(),
        budgets =
            GoalBudgets(
                maxModelCalls = 10,
                maxToolCalls = 20,
                maxTotalTokens = 100_000L,
                maxDurationMillis = 3_600_000L,
                maxWakeDurationMillis = 600_000L,
                maxRetries = 2,
            ),
        state = state,
        planId = null,
        planHash = null,
        nextCheckpoint = nextCheckpoint,
        correlationId = "corr-$id",
        runCount = 1,
        modelCalls = 0,
        toolCalls = 0,
        totalTokens = 0L,
        runTimeMillis = 0L,
        currentWakeMillis = currentWakeMillis,
        retries = 0,
        lastWakeReason = GoalWakeReason.USER_OPEN.name,
        error = null,
        finishReason = null,
    )

    private fun HelixStorage.seedCall(
        id: String,
        turnId: String,
        callId: String,
        name: String,
        argsJson: String,
        state: ToolCallState,
    ) {
        toolCalls.append(id, turnId, callId, name, "1", argsJson, state.name)
    }

    private fun isolatedStorage(
        context: Context,
        suffix: String,
    ): HelixStorage =
        HelixStorage.open(
            context,
            "recovery-test-$suffix.db",
            File(context.filesDir, "helix-content-recovery-$suffix"),
        )
}

private class FixedClock(
    private val millis: Long,
) : Clock {
    override fun now(): Instant = Instant.ofEpochMilli(millis)
}
