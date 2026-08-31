package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerCancellationTest {
    @Test
    fun cancelIsPossibleFromEveryLivePhase() {
        val livePhases =
            listOf(
                Phase.CREATED,
                Phase.BUILDING_CONTEXT,
                Phase.WAITING_MODEL,
                Phase.RECEIVING_MODEL,
                Phase.WAITING_APPROVAL,
                Phase.RUNNING_TOOL,
                Phase.RECORDING_TOOL_RESULT,
            )
        for (phase in livePhases) {
            val state = driveTo(phase)
            val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.CancelRequested)
            assertEquals("cancel from $phase", Phase.CANCELLING, step.state.phase)
            assertEquals(
                "cancel effects from $phase",
                listOf<TurnEffect>(TurnEffect.CancelInFlight),
                step.effects,
            )
        }
    }

    @Test
    fun cancelDuringRunningToolTracksUncertainCandidate() {
        val running = driveTo(Phase.RUNNING_TOOL)
        val cancelling = TurnReducer.reduce(running, TurnEvent.Lifecycle.CancelRequested).state
        assertEquals(Fixtures.tool(1), cancelling.uncertainToolCallId)
    }

    @Test
    fun cancelFromWaitingApprovalHasNoUncertainty() {
        val waiting = driveTo(Phase.WAITING_APPROVAL)
        val cancelling = TurnReducer.reduce(waiting, TurnEvent.Lifecycle.CancelRequested).state
        assertNull(cancelling.uncertainToolCallId)
    }

    @Test
    fun cancelFinishedWithoutUncertaintyCancelsTurn() {
        val cancelling = driveTo(Phase.CANCELLING)
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.CancelFinished(null))
        assertEquals(Phase.CANCELLED, step.state.phase)
        assertEquals("cancelled", step.state.finishReason)
        assertNull(step.state.error)
        assertNull(step.state.uncertainToolCallId)
        assertEquals(listOf<TurnEffect>(TurnEffect.CompleteTurn("cancelled")), step.effects)
    }

    @Test
    fun cancelFinishedWithUncertaintyRetainsIt() {
        val running = driveTo(Phase.RUNNING_TOOL)
        val cancelling = TurnReducer.reduce(running, TurnEvent.Lifecycle.CancelRequested).state
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.CancelFinished(Fixtures.tool(1)))
        assertEquals(Phase.CANCELLED, step.state.phase)
        assertEquals(Fixtures.tool(1), step.state.uncertainToolCallId)
    }

    @Test
    fun cancelIsRejectedFromTerminalPhases() {
        for (phase in listOf(Phase.COMPLETED, Phase.FAILED, Phase.CANCELLED)) {
            val state = driveTo(phase)
            val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.CancelRequested)
            assertTrue("cancel from $phase must be ignored", step.ignored)
            assertEquals(state, step.state)
        }
    }

    @Test
    fun cancelIsRejectedWhileCancellingOrInterrupted() {
        for (phase in listOf(Phase.CANCELLING, Phase.INTERRUPTED)) {
            val state = driveTo(phase)
            val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.CancelRequested)
            assertTrue("cancel from $phase must be ignored", step.ignored)
        }
    }

    @Test
    fun cancelFinishedOutsideCancellingIsIgnored() {
        val state = driveTo(Phase.CREATED)
        val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.CancelFinished(null))
        assertTrue(step.ignored)
        assertFalse(step.state.isTerminal())
    }

    private fun twoCallResponse(): ModelTerminal.ToolCalls =
        ModelTerminal.ToolCalls(
            listOf(Fixtures.modelToolCall(1), Fixtures.modelToolCall(2)),
        )

    /** Runs to RUNNING_TOOL with call 1 executing and call 2 queued PENDING behind it. */
    private fun runningWithQueuedCall(): TurnState {
        val receiving = driveTo(Phase.RECEIVING_MODEL)
        val started = TurnReducer.reduce(receiving, TurnEvent.Model.Finished(Fixtures.call(1), 400, twoCallResponse()))
        assertEquals(Phase.RUNNING_TOOL, started.state.phase)
        return TurnReducer
            .reduce(started.state, TurnEvent.Tool.ExecutionFinished(Fixtures.tool(1), Fixtures.success()))
            .state // RECORDING_TOOL_RESULT with call 2 still queued
    }

    @Test
    fun cancelRecordsCancelledOutcomeForQueuedUnexecutedCalls() {
        // After call 1 finishes, call 2 sits PENDING in the queue; cancelling the turn must
        // give it the terminal Cancelled outcome (never executed) so no stale PENDING row
        // survives under a CANCELLED turn.
        val recording = runningWithQueuedCall()
        val cancelling = TurnReducer.reduce(recording, TurnEvent.Lifecycle.CancelRequested).state
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.CancelFinished(null))
        assertEquals(Phase.CANCELLED, step.state.phase)
        val outcomes = step.state.recordedOutcomes.associate { it.toolCallId to it.outcome }
        assertTrue("queued call must be recorded Cancelled", outcomes[Fixtures.tool(2)] is ToolOutcome.Cancelled)
        assertTrue(step.effects.any { it == TurnEffect.RecordToolResult(Fixtures.tool(2), ToolOutcome.Cancelled) })
    }

    @Test
    fun cancelWithExecutingUncertainCallAndQueuedBehindRecordsOnlyTheQueued() {
        // The safety-critical composition: call 1 is executing (its external effect is unknown
        // the moment cancellation lands) while call 2 sits PENDING behind it. Cancel must leave
        // a durable Cancelled outcome for call 2 and record NO outcome for the uncertain call 1
        // (a recorded Cancelled on an executing call would destroy the uncertainty tracking the
        // HXA-015 review flow depends on).
        val receiving = driveTo(Phase.RECEIVING_MODEL)
        val running =
            TurnReducer.reduce(receiving, TurnEvent.Model.Finished(Fixtures.call(1), 400, twoCallResponse())).state
        assertEquals(Phase.RUNNING_TOOL, running.phase)
        val cancelling = TurnReducer.reduce(running, TurnEvent.Lifecycle.CancelRequested).state
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.CancelFinished(Fixtures.tool(1)))
        assertEquals(Phase.CANCELLED, step.state.phase)
        assertEquals(Fixtures.tool(1), step.state.uncertainToolCallId)
        val outcomes = step.state.recordedOutcomes.associate { it.toolCallId to it.outcome }
        assertTrue("queued call 2 must be recorded Cancelled", outcomes[Fixtures.tool(2)] is ToolOutcome.Cancelled)
        assertTrue("uncertain executing call 1 must not be recorded", outcomes[Fixtures.tool(1)] == null)
    }

    @Test
    fun discardAfterDeathWithUncertainCallAndQueuedBehindRecordsOnlyTheQueued() {
        // Same composition through process death: the frozen queue keeps call 2 PENDING behind
        // the executing call 1; discarding the interrupted turn must record call 2 Cancelled
        // and leave the uncertain call 1 untouched (it is resolved only by the review flow).
        val receiving = driveTo(Phase.RECEIVING_MODEL)
        val running =
            TurnReducer.reduce(receiving, TurnEvent.Model.Finished(Fixtures.call(1), 400, twoCallResponse())).state
        val dead = TurnReducer.afterProcessDeath(running)
        assertEquals(Phase.INTERRUPTED, dead.phase)
        assertEquals(Fixtures.tool(1), dead.uncertainToolCallId)
        val step = TurnReducer.reduce(dead, TurnEvent.Lifecycle.TurnDiscarded)
        assertEquals(Phase.CANCELLED, step.state.phase)
        val outcomes = step.state.recordedOutcomes.associate { it.toolCallId to it.outcome }
        assertTrue("queued call 2 must be recorded Cancelled", outcomes[Fixtures.tool(2)] is ToolOutcome.Cancelled)
        assertTrue("uncertain executing call 1 must not be recorded", outcomes[Fixtures.tool(1)] == null)
    }

    @Test
    fun executionFinishedWithDeniedOutcomeIsIgnored() {
        // A denied call never executes (DENIED is the approval-gate outcome, audit-only); a
        // Denied outcome arriving in ExecutionFinished is off-contract and must be dropped per
        // the illegal-event contract instead of crashing the reduce with a state-machine
        // violation (RUNNING has no edge to DENIED).
        val running = driveTo(Phase.RUNNING_TOOL)
        val step =
            TurnReducer.reduce(
                running,
                TurnEvent.Tool.ExecutionFinished(Fixtures.tool(1), ToolOutcome.Denied("denied mid-execution")),
            )
        assertTrue("off-contract Denied outcome must be ignored", step.ignored)
        assertEquals(running, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun cancelKeepsUncertainCallOutOfRecordedOutcomes() {
        val recording = runningWithQueuedCall()
        val cancelling = TurnReducer.reduce(recording, TurnEvent.Lifecycle.CancelRequested).state
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.CancelFinished(Fixtures.tool(2)))
        assertEquals(Fixtures.tool(2), step.state.uncertainToolCallId)
        // The uncertain call's side effect is unknown: it is tracked, not recorded as cancelled.
        val outcomes = step.state.recordedOutcomes
        assertTrue("uncertain call must not be recorded", outcomes.none { it.toolCallId == Fixtures.tool(2) })
    }
}
