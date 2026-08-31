package com.helix.core.agent

import com.helix.core.model.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.core.model.TurnState as Phase

class TurnReducerToolLoopTest {
    @Test
    fun toolCallWithoutApprovalExecutesDirectly() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step =
            reduce(
                state,
                TurnEvent.Model.Finished(
                    activeCall(state),
                    400,
                    ModelTerminal.ToolCalls(listOf(Fixtures.modelToolCall(1, false))),
                ),
            )
        assertEquals(Phase.RUNNING_TOOL, step.state.phase)
        val effect = step.effects.single() as TurnEffect.ExecuteToolCall
        assertEquals(Fixtures.tool(1), effect.call.toolCallId)
        assertEquals(ToolCallState.RUNNING, effect.call.state)
    }

    @Test
    fun toolResultReentersContextModelLoop() {
        val recording = driveTo(Phase.RECORDING_TOOL_RESULT)
        val step = reduce(recording, TurnEvent.Tool.ResultsRecorded)
        assertEquals(Phase.BUILDING_CONTEXT, step.state.phase)
        assertTrue(step.state.pendingCalls.isEmpty())
        assertEquals(listOf<TurnEffect>(TurnEffect.BuildContext), step.effects)
        // The loop continues into a new model call.
        val next =
            reduce(
                step.state,
                TurnEvent.Lifecycle.ContextReady(Fixtures.call(2), 1200),
            )
        assertEquals(Phase.WAITING_MODEL, next.state.phase)
        val start = next.effects.single() as TurnEffect.StartModelCall
        assertEquals(Fixtures.call(2), start.callId)
        assertEquals(2, start.step)
    }

    @Test
    fun approvalRequiredCallWaitsForApproval() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val step =
            reduce(
                state,
                TurnEvent.Model.Finished(
                    activeCall(state),
                    400,
                    ModelTerminal.ToolCalls(listOf(Fixtures.modelToolCall(1, true))),
                ),
            )
        assertEquals(Phase.WAITING_APPROVAL, step.state.phase)
        val effect = step.effects.single() as TurnEffect.RequestApproval
        assertEquals(ToolCallState.AWAITING_APPROVAL, effect.call.state)
    }

    @Test
    fun approvalGrantedExecutesCallWithProofBound() {
        val waiting = driveTo(Phase.WAITING_APPROVAL)
        val step = reduce(waiting, TurnEvent.Tool.CallApproved(Fixtures.tool(1), Fixtures.approval(1)))
        assertEquals(Phase.RUNNING_TOOL, step.state.phase)
        val effect = step.effects.single() as TurnEffect.ExecuteToolCall
        assertEquals(Fixtures.approval(1), effect.call.approvalId)
        assertEquals(ToolCallState.RUNNING, effect.call.state)
    }

    @Test
    fun approvalDeniedRecordsDeniedResultAndLoopsBack() {
        val waiting = driveTo(Phase.WAITING_APPROVAL)
        val denied = reduce(waiting, TurnEvent.Tool.CallDenied(Fixtures.tool(1), "user denied the write"))
        assertEquals(Phase.RECORDING_TOOL_RESULT, denied.state.phase)
        val effect = denied.effects.single() as TurnEffect.RecordToolResult
        assertTrue(effect.outcome is ToolOutcome.Denied)
        assertEquals(listOf(Fixtures.tool(1)), denied.state.deniedCalls)
        assertEquals(1, denied.state.recordedOutcomes.size)
        // The agent re-enters the model loop and may adjust its plan.
        val back = reduce(denied.state, TurnEvent.Tool.ResultsRecorded)
        assertEquals(Phase.BUILDING_CONTEXT, back.state.phase)
    }

    private fun twoCallResponse(): ModelTerminal.ToolCalls {
        val first = Fixtures.modelToolCall(1, true)
        val second = Fixtures.modelToolCall(2, false)
        return ModelTerminal.ToolCalls(listOf(first, second))
    }

    @Test
    fun multipleToolCallsExecuteSerially() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val first = reduce(state, TurnEvent.Model.Finished(activeCall(state), 400, twoCallResponse()))
        assertEquals(Phase.WAITING_APPROVAL, first.state.phase)

        val approved = reduce(first.state, TurnEvent.Tool.CallApproved(Fixtures.tool(1), Fixtures.approval(1)))
        assertEquals(Phase.RUNNING_TOOL, approved.state.phase)

        val executed = reduce(approved.state, TurnEvent.Tool.ExecutionFinished(Fixtures.tool(1), Fixtures.success()))
        assertEquals(Phase.RECORDING_TOOL_RESULT, executed.state.phase)

        // Second call of the same response: no approval needed -> straight to execution.
        val second = reduce(executed.state, TurnEvent.Tool.ResultsRecorded)
        assertEquals(Phase.RUNNING_TOOL, second.state.phase)
        val effect = second.effects.single() as TurnEffect.ExecuteToolCall
        assertEquals(Fixtures.tool(2), effect.call.toolCallId)

        val executedSecond =
            reduce(second.state, TurnEvent.Tool.ExecutionFinished(Fixtures.tool(2), Fixtures.success()))
        assertEquals(Phase.RECORDING_TOOL_RESULT, executedSecond.state.phase)

        // Queue empty -> context/model loop.
        val back = reduce(executedSecond.state, TurnEvent.Tool.ResultsRecorded)
        assertEquals(Phase.BUILDING_CONTEXT, back.state.phase)
        assertEquals(listOf(Fixtures.tool(1), Fixtures.tool(2)), back.state.recordedOutcomes.map { it.toolCallId })
    }

    @Test
    fun denialMidQueueContinuesWithNextCall() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val first = reduce(state, TurnEvent.Model.Finished(activeCall(state), 400, twoCallResponse()))
        val denied = reduce(first.state, TurnEvent.Tool.CallDenied(Fixtures.tool(1), "denied"))
        val next = reduce(denied.state, TurnEvent.Tool.ResultsRecorded)
        assertEquals(Phase.RUNNING_TOOL, next.state.phase)
        assertEquals(
            Fixtures.tool(2),
            next.state.pendingCalls
                .single()
                .toolCallId,
        )
    }

    @Test
    fun staleToolEventIsIgnored() {
        val state = driveTo(Phase.RECEIVING_MODEL)
        val response =
            ModelTerminal.ToolCalls(listOf(Fixtures.modelToolCall(1, false), Fixtures.modelToolCall(2, false)))
        val first = reduce(state, TurnEvent.Model.Finished(activeCall(state), 400, response))
        // The second call finished "early": not the active call, so it is ignored.
        val step =
            TurnReducer.reduce(first.state, TurnEvent.Tool.ExecutionFinished(Fixtures.tool(2), Fixtures.success()))
        assertTrue(step.ignored)
        assertEquals(first.state, step.state)
    }

    @Test
    fun approvalForInactiveCallIsIgnored() {
        val waiting = driveTo(Phase.WAITING_APPROVAL)
        val step = TurnReducer.reduce(waiting, TurnEvent.Tool.CallApproved(Fixtures.tool(99), Fixtures.approval(1)))
        assertTrue(step.ignored)
        assertEquals(waiting, step.state)
    }

    @Test
    fun duplicateToolCallIdsInResponseAreRejected() {
        assertThrows<IllegalArgumentException> {
            ModelTerminal.ToolCalls(listOf(Fixtures.modelToolCall(1), Fixtures.modelToolCall(1)))
        }
    }

    @Test
    fun emptyToolCallResponseIsRejected() {
        assertThrows<IllegalArgumentException> { ModelTerminal.ToolCalls(emptyList()) }
    }
}
