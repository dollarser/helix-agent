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
}
