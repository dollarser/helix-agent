package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.GoalId
import com.helix.core.model.ProviderId
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the [AgentRuntime] unified entry point (HX2-01): the command and snapshot
 * value types, and that the interface is implementable and observable end-to-end (submit ->
 * observe -> cancel) via a fake runtime.
 */
class AgentRuntimeContractTest {
    private val sessionId = SessionId("s1")
    private val providerId = ProviderId("p1")
    private val turnId = TurnId("t1")
    private val goalId = GoalId("g1")
    private val budgets = TurnBudgets(8, 9, 128_000, 4_096, 160_000)

    // --- SubmitTurnCommand is a data carrier over the turn-start intent ---

    @Test
    fun aCommandCarriesTheTurnStartIntent() {
        val command =
            SubmitTurnCommand(
                sessionId,
                providerId,
                AgentMode.CHAT,
                "hi",
                budgets,
                clientRequestId = "req-1",
            )
        assertEquals(sessionId, command.session)
        assertEquals(AgentMode.CHAT, command.mode)
        assertEquals("hi", command.text)
        assertFalse(command.chatToolsEnabled)
        assertEquals(ReasoningEffort.OFF, command.reasoning)
        assertEquals(null, command.goalId)
        assertEquals(null, command.retryTurnId)
    }

    @Test
    fun aGoalTurnBindsItsGoalAndCarriesItsRunControl() {
        val command =
            SubmitTurnCommand(
                session = sessionId,
                providerId = providerId,
                mode = AgentMode.GOAL,
                text = null,
                budgets = budgets,
                reasoning = ReasoningEffort.MEDIUM,
                goalId = goalId,
                clientRequestId = "req-2",
            )
        assertEquals(goalId, command.goalId)
        assertEquals(ReasoningEffort.MEDIUM, command.reasoning)
        assertTrue(command.text == null)
    }

    @Test
    fun aCommandWithoutADriverIsRejected() {
        // No user text, no bound goal, no retry: nothing could drive the turn.
        assertThrows(IllegalArgumentException::class.java) {
            SubmitTurnCommand(sessionId, providerId, AgentMode.CHAT, null, budgets, clientRequestId = "req-3")
        }
    }

    @Test
    fun aRetryTurnIsADriverOnItsOwn() {
        val command =
            SubmitTurnCommand(
                sessionId,
                providerId,
                AgentMode.CHAT,
                null,
                budgets,
                retryTurnId = turnId,
                clientRequestId = "req-4",
            )
        assertEquals(turnId, command.retryTurnId)
    }

    @Test
    fun aBlankClientRequestIdIsRejected() {
        // The stable dedup id is part of the submission's identity: a blank one would make
        // idempotency-by-id meaningless, so the contract refuses it (fail-closed).
        assertThrows(IllegalArgumentException::class.java) {
            SubmitTurnCommand(sessionId, providerId, AgentMode.CHAT, "hi", budgets, clientRequestId = "  ")
        }
    }

    // --- TurnSnapshot projects the reducer state onto the UI ---

    @Test
    fun aSnapshotIsTerminalWhenItsPhaseIsTerminal() {
        val running = TurnSnapshot(turnId, TurnState.RUNNING_TOOL)
        val done = running.copy(phase = TurnState.COMPLETED, assistantText = "all done")
        assertFalse(running.isTerminal)
        assertTrue(done.isTerminal)
        assertEquals("all done", done.assistantText)
    }

    @Test
    fun aTerminalSnapshotCanCarryTheCompletionReportAndLedger() {
        val ledger = TaskLedger(listOf(TaskItem("a", "title", TaskItemState.DONE)))
        val report = CompletionReport(CompletionStatus.COMPLETE, "done")
        val snapshot = TurnSnapshot(turnId, TurnState.COMPLETED, completionReport = report, taskLedger = ledger)
        assertEquals(report, snapshot.completionReport)
        assertEquals(ledger, snapshot.taskLedger)
    }

    // --- the contract is implementable and observable end-to-end ---

    @Test
    fun aRuntimeImplementsSubmitCancelObserve() {
        val runtime = FakeAgentRuntime(turnId)
        runBlocking {
            val submitted =
                runtime.submit(
                    SubmitTurnCommand(
                        sessionId,
                        providerId,
                        AgentMode.CHAT,
                        "hi",
                        budgets,
                        clientRequestId = "req-5",
                    ),
                )
            assertEquals(turnId, submitted)

            val frames = runtime.observe(turnId).take(2).toList()
            assertEquals(listOf(TurnState.WAITING_MODEL, TurnState.COMPLETED), frames.map { it.phase })

            val cancelled = runtime.cancel(turnId)
            assertTrue(cancelled is CancelResult.AlreadyTerminal)
        }
    }

    @Test
    fun aResubmittedClientRequestIdReturnsTheSameTurnNotASecondOne() {
        // The contract is idempotent by clientRequestId (HX2-01 §2e): a re-driven submission
        // carrying the same id returns the already-started turn; a DIFFERENT id starts a new one.
        val runtime = IdempotentFakeRuntime()
        val command =
            SubmitTurnCommand(
                sessionId,
                providerId,
                AgentMode.CHAT,
                "hi",
                budgets,
                clientRequestId = "req-idem",
            )
        runBlocking {
            val first = runtime.submit(command)
            val resubmitted = runtime.submit(command)
            assertEquals(first, resubmitted)
            assertEquals(1, runtime.turnsStarted) // the re-drive did not start a second turn

            val different = runtime.submit(command.copy(clientRequestId = "req-other"))
            assertTrue(different != first)
            assertEquals(2, runtime.turnsStarted)
        }
    }

    private class IdempotentFakeRuntime : AgentRuntime {
        private val started = HashMap<String, TurnId>()
        var turnsStarted = 0
            private set

        override suspend fun submit(command: SubmitTurnCommand): TurnId {
            started[command.clientRequestId]?.let { return it }
            val id = TurnId("t$turnsStarted")
            started[command.clientRequestId] = id
            turnsStarted++
            return id
        }

        override suspend fun cancel(turnId: TurnId): CancelResult =
            if (started.values.contains(turnId)) {
                CancelResult.AlreadyTerminal(TurnState.COMPLETED)
            } else {
                CancelResult.NotFound
            }

        override fun observe(turnId: TurnId): Flow<TurnSnapshot> =
            flowOf(TurnSnapshot(turnId, TurnState.COMPLETED, assistantText = "done"))
    }

    private class FakeAgentRuntime(
        private val id: TurnId,
    ) : AgentRuntime {
        override suspend fun submit(command: SubmitTurnCommand): TurnId = id

        override suspend fun cancel(turnId: TurnId): CancelResult =
            if (turnId == id) CancelResult.AlreadyTerminal(TurnState.COMPLETED) else CancelResult.NotFound

        override fun observe(turnId: TurnId): Flow<TurnSnapshot> =
            flowOf(
                TurnSnapshot(id, TurnState.WAITING_MODEL),
                TurnSnapshot(id, TurnState.COMPLETED, assistantText = "done"),
            )
    }
}
