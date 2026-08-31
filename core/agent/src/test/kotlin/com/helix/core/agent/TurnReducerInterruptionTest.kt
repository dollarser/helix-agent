package com.helix.core.agent

import com.helix.core.model.ErrorCode
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerInterruptionTest {
    @Test
    fun processDeathInterruptsOnlyLivePhases() {
        for (phase in Phase.entries) {
            val expected = if (!phase.isTerminal && phase != Phase.INTERRUPTED) Phase.INTERRUPTED else phase
            val state = TurnReducer.afterProcessDeath(driveTo(phase))
            assertEquals("process death from $phase", expected, state.phase)
        }
    }

    @Test
    fun deathDuringRunningToolMarksUncertainCall() {
        val running = driveTo(Phase.RUNNING_TOOL)
        val interrupted = TurnReducer.afterProcessDeath(running)
        assertEquals(Phase.INTERRUPTED, interrupted.phase)
        assertEquals(Fixtures.tool(1), interrupted.uncertainToolCallId)
    }

    @Test
    fun deathDuringWaitingApprovalHasNoUncertainty() {
        val waiting = driveTo(Phase.WAITING_APPROVAL)
        val interrupted = TurnReducer.afterProcessDeath(waiting)
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
    fun resumeIsRejectedWhileUncertaintyUnresolved() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.RUNNING_TOOL))
        val step = TurnReducer.reduce(interrupted, TurnEvent.Lifecycle.TurnResumed)
        assertTrue(step.ignored)
        assertEquals(interrupted, step.state)
    }

    @Test
    fun resumeAfterResolutionReentersContextModelLoop() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.RUNNING_TOOL))
        val resolved =
            TurnReducer.reduce(interrupted, TurnEvent.Lifecycle.UncertainToolCallResolved(Fixtures.success()))
        assertNull(resolved.state.uncertainToolCallId)
        val recorded = resolved.state.recordedOutcomes.single()
        assertEquals(Fixtures.tool(1), recorded.toolCallId)
        assertTrue(recorded.outcome is ToolOutcome.Succeeded)

        val resumed = TurnReducer.reduce(resolved.state, TurnEvent.Lifecycle.TurnResumed)
        assertEquals(Phase.BUILDING_CONTEXT, resumed.state.phase)
        assertEquals(listOf<TurnEffect>(TurnEffect.BuildContext), resumed.effects)
    }

    @Test
    fun resolutionWithWrongOutcomeForTrackedCallStillAppliesToTrackedCall() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.RUNNING_TOOL))
        val resolved =
            TurnReducer.reduce(
                interrupted,
                TurnEvent.Lifecycle.UncertainToolCallResolved(
                    ToolOutcome.Failed(Fixtures.error(ErrorCode.EXECUTION, "tool run did not complete")),
                ),
            )
        val recorded = resolved.state.recordedOutcomes.single()
        assertEquals(Fixtures.tool(1), recorded.toolCallId)
        assertTrue(recorded.outcome is ToolOutcome.Failed)
    }

    @Test
    fun resolutionWithoutUncertaintyIsIgnored() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.RECEIVING_MODEL))
        val step = TurnReducer.reduce(interrupted, TurnEvent.Lifecycle.UncertainToolCallResolved(Fixtures.success()))
        assertTrue(step.ignored)
    }

    @Test
    fun resumeRecordsUnexecutedCallsAsInterruptedFailures() {
        // Two calls in the interrupted response: the first was awaiting approval (never
        // executed), so both must be recorded as failed-with-interrupt on resume.
        val state = driveTo(Phase.WAITING_APPROVAL)
        val withSecond =
            state.copy(pendingCalls = state.pendingCalls + secondPendingCall())
        val interrupted = TurnReducer.afterProcessDeath(withSecond)
        val resumed = TurnReducer.reduce(interrupted, TurnEvent.Lifecycle.TurnResumed)
        assertEquals(Phase.BUILDING_CONTEXT, resumed.state.phase)
        assertTrue(resumed.state.pendingCalls.isEmpty())
        val recorded = resumed.state.recordedOutcomes
        assertEquals(listOf(Fixtures.tool(1), Fixtures.tool(2)), recorded.map { it.toolCallId })
        for (outcome in recorded) {
            val failed = outcome.outcome as? ToolOutcome.Failed
            assertTrue("unexecuted call must be recorded as failed, was ${outcome.outcome}", failed != null)
            assertEquals(ErrorCode.INTERRUPTED, failed?.error?.code)
        }
    }

    @Test
    fun discardFromInterruptedCancelsTurn() {
        val interrupted = TurnReducer.afterProcessDeath(driveTo(Phase.WAITING_APPROVAL))
        val step = TurnReducer.reduce(interrupted, TurnEvent.Lifecycle.TurnDiscarded)
        assertEquals(Phase.CANCELLED, step.state.phase)
        assertEquals("discarded", step.state.finishReason)
        assertTrue(step.state.pendingCalls.isEmpty())
        assertEquals(listOf<TurnEffect>(TurnEffect.CompleteTurn("discarded")), step.effects)
    }

    @Test
    fun discardFromCancellingCancelsTurn() {
        val cancelling = driveTo(Phase.CANCELLING)
        val step = TurnReducer.reduce(cancelling, TurnEvent.Lifecycle.TurnDiscarded)
        assertEquals(Phase.CANCELLED, step.state.phase)
        assertEquals("discarded", step.state.finishReason)
    }

    @Test
    fun discardFromLivePhaseIsIgnored() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step = TurnReducer.reduce(state, TurnEvent.Lifecycle.TurnDiscarded)
        assertTrue(step.ignored)
    }

    private fun secondPendingCall(): PendingToolCall =
        PendingToolCall(
            toolCallId = Fixtures.tool(2),
            toolName = ToolName("write"),
            toolVersion = ToolVersion(1),
            requiresApproval = false,
            state = ToolCallState.PENDING,
        )
}
