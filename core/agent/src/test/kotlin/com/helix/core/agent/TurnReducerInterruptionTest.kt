package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerInterruptionTest {
    @Test
    fun processDeathInterruptsOnlyAdvancingPhases() {
        for (phase in Phase.entries) {
            val expected =
                if (!phase.isTerminal && phase != Phase.NEEDS_REVIEW) {
                    Phase.INTERRUPTED
                } else {
                    phase
                }
            val state = TurnReducer.afterProcessDeath(driveTo(phase))
            assertEquals("process death from $phase", expected, state.phase)
        }
    }

    @Test
    fun deathDuringRunningToolMarksUncertainCall() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.RUNNING_TOOL))
        assertEquals(Phase.INTERRUPTED, interrupted.phase)
        assertEquals(Fixtures.tool(1), interrupted.uncertainToolCallId)
    }

    @Test
    fun deathDuringWaitingApprovalHasNoUncertainty() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.WAITING_APPROVAL))
        assertEquals(Phase.INTERRUPTED, interrupted.phase)
        assertNull(interrupted.uncertainToolCallId)
    }

    @Test
    fun deathDuringCancellingKeepsCandidate() {
        val running = driveTo(Phase.RUNNING_TOOL)
        val cancelling = TurnReducer.reduce(running, TurnEvent.Lifecycle.CancelRequested).state
        val interrupted = TurnReducer.afterProcessDeath(cancelling)
        assertEquals(Phase.INTERRUPTED, interrupted.phase)
        assertEquals(Fixtures.tool(1), interrupted.uncertainToolCallId)
    }

    @Test
    fun processDiedEventMatchesProcessDeathMapping() {
        val running = driveTo(Phase.RUNNING_TOOL)
        val viaEvent = TurnReducer.reduce(running, TurnEvent.Lifecycle.ProcessDied).state
        val viaMapping = TurnReducer.afterProcessDeath(running)
        assertEquals(viaMapping, viaEvent)
    }

    @Test
    fun interruptedTurnIsExecutionTerminalAndCannotBeDiscardedBackIntoLifecycle() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.WAITING_MODEL))
        assertTrue(interrupted.phase.isTerminal)
        val discarded = TurnReducer.reduce(interrupted, TurnEvent.Lifecycle.TurnDiscarded)
        assertTrue(discarded.ignored)
        assertEquals(interrupted, discarded.state)
    }

    @Test
    fun discardFromCancellingStillClosesLiveCancellation() {
        val cancelling = driveTo(Phase.CANCELLING)
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.TurnDiscarded)
        assertEquals(Phase.CANCELLED, step.state.phase)
        assertEquals("discarded", step.state.finishReason)
    }

    @Test
    fun deathDuringWaitingModelClearsCommittedCallAndClosesAttempt() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.WAITING_MODEL))
        assertEquals(Phase.INTERRUPTED, interrupted.phase)
        assertNull(interrupted.committedCallId)
        assertNull(interrupted.activeCallId)
        assertNull(interrupted.uncertainToolCallId)
        assertTrue(interrupted.phase.isTerminal)
    }

    @Test
    fun deathDuringReceivingModelClearsActiveCallAndClosesAttempt() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.RECEIVING_MODEL))
        assertEquals(Phase.INTERRUPTED, interrupted.phase)
        assertNull(interrupted.committedCallId)
        assertNull(interrupted.activeCallId)
        assertTrue(interrupted.phase.isTerminal)
    }

    @Test
    fun processDiedOnTerminalTurnIsIgnored() {
        for (phase in listOf(Phase.INTERRUPTED, Phase.COMPLETED, Phase.FAILED, Phase.CANCELLED)) {
            val state =
                if (phase ==
                    Phase.INTERRUPTED
                ) {
                    TurnReducer.afterProcessDeath(driveTo(Phase.RECEIVING_MODEL))
                } else {
                    driveTo(phase)
                }
            val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.ProcessDied)
            assertTrue("ProcessDied on $phase must be ignored", step.ignored)
            assertEquals(state, step.state)
        }
    }

    @Test
    fun processDiedOnNeedsReviewTurnIsIgnored() {
        val parked = driveTo(Phase.NEEDS_REVIEW)
        val step = TurnReducer.reduce(parked, TurnEvent.Lifecycle.ProcessDied)
        assertTrue(step.ignored)
        assertEquals(parked, step.state)
    }

    @Test
    fun discardFromLivePhaseIsIgnored() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.TurnDiscarded)
        assertTrue(step.ignored)
    }
}
