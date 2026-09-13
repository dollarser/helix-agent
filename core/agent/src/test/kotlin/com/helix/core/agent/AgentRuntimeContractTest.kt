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
 * observe -> resume / cancel) via a fake runtime.
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
        val command = SubmitTurnCommand(sessionId, providerId, AgentMode.CHAT, "hi", budgets)
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
            )
        assertEquals(goalId, command.goalId)
        assertEquals(ReasoningEffort.MEDIUM, command.reasoning)
        assertTrue(command.text == null)
    }

    @Test
    fun aCommandWithoutADriverIsRejected() {
        // No user text, no bound goal, no retry: nothing could drive the turn.
        assertThrows(IllegalArgumentException::class.java) {
            SubmitTurnCommand(sessionId, providerId, AgentMode.CHAT, null, budgets)
        }
    }

    @Test
    fun aRetryTurnIsADriverOnItsOwn() {
        val command = SubmitTurnCommand(sessionId, providerId, AgentMode.CHAT, null, budgets, retryTurnId = turnId)
        assertEquals(turnId, command.retryTurnId)
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
    fun aRuntimeImplementsSubmitResumeCancelObserve() {
        val runtime = FakeAgentRuntime(turnId)
        runBlocking {
            val submitted = runtime.submit(SubmitTurnCommand(sessionId, providerId, AgentMode.CHAT, "hi", budgets))
            assertEquals(turnId, submitted)

            val frames = runtime.observe(turnId).take(2).toList()
            assertEquals(listOf(TurnState.WAITING_MODEL, TurnState.COMPLETED), frames.map { it.phase })

            val resumed = runtime.resume(turnId)
            assertTrue(resumed is ResumeResult.AlreadyTerminal)

            val unknownResume = runtime.resume(TurnId("nope"))
            assertTrue(unknownResume is ResumeResult.NotFound)

            val cancelled = runtime.cancel(turnId)
            assertTrue(cancelled is CancelResult.AlreadyTerminal)
        }
    }

    private class FakeAgentRuntime(
        private val id: TurnId,
    ) : AgentRuntime {
        override suspend fun submit(command: SubmitTurnCommand): TurnId = id

        override suspend fun resume(turnId: TurnId): ResumeResult =
            if (turnId == id) ResumeResult.AlreadyTerminal(TurnState.COMPLETED) else ResumeResult.NotFound

        override suspend fun cancel(turnId: TurnId): CancelResult =
            if (turnId == id) CancelResult.AlreadyTerminal(TurnState.COMPLETED) else CancelResult.NotFound

        override fun observe(turnId: TurnId): Flow<TurnSnapshot> =
            flowOf(
                TurnSnapshot(id, TurnState.WAITING_MODEL),
                TurnSnapshot(id, TurnState.COMPLETED, assistantText = "done"),
            )
    }
}
