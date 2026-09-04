package com.helix.app.chat

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStreamStateTest {
    @Test
    fun textDeltasExposeOneReceivingTransitionAndAccumulatedText() {
        val state = ModelStreamState()

        val first = state.apply(ModelEvent.TextDelta("你"))
        val second = state.apply(ModelEvent.TextDelta("好"))

        assertTrue(first.textChanged)
        assertTrue(first.receivingStarted)
        assertTrue(second.textChanged)
        assertFalse(second.receivingStarted)
        assertEquals("你好", state.text)
        assertEquals(TurnState.COMPLETED, state.terminal(cancelled = false).state)
    }

    @Test
    fun nullableUsageNeverInventsZero() {
        val state = ModelStreamState()

        state.apply(ModelEvent.Usage(null, null))
        assertNull(state.usageJson)
        state.apply(ModelEvent.Usage(12, null))
        assertEquals("{\"inputTokens\":12}", state.usageJson)
        state.apply(ModelEvent.Usage(null, 7))
        assertEquals("{\"outputTokens\":7}", state.usageJson)
    }

    @Test
    fun finishedToolCallsUseProviderIndexOrderAndKeepRawArgumentFragments() {
        val state = ModelStreamState()
        state.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-b"), "b"))
        state.apply(ModelEvent.ToolArgumentsDelta(1, "{\"b\":"))
        state.apply(ModelEvent.ToolArgumentsDelta(1, "2}"))
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-a"), "a"))
        state.apply(ModelEvent.ToolArgumentsDelta(0, "{}"))
        state.apply(ModelEvent.ToolCallFinished(1))
        state.apply(ModelEvent.ToolCallFinished(0))

        assertEquals(listOf("call-a", "call-b"), state.finishedToolCalls.map { it.callId })
        assertEquals("{\"b\":2}", state.finishedToolCalls.last().arguments)
        assertEquals(TurnState.COMPLETED, state.terminal(cancelled = false).state)
    }

    @Test
    fun orphanArgumentAndFinishEventsFailClosed() {
        val state = ModelStreamState()

        state.apply(ModelEvent.ToolArgumentsDelta(3, "{}"))
        state.apply(ModelEvent.ToolCallFinished(3))

        assertTrue(state.finishedToolCalls.isEmpty())
        assertEquals(TurnState.FAILED, state.terminal(cancelled = false).state)
        assertEquals("TOOL_STREAM_INVALID", state.terminal(cancelled = false).errorCode)
    }

    @Test
    fun totalToolArgumentsOverflowFailsClosedBeforeDispatch() {
        val state = ModelStreamState(maxToolArgumentsChars = 4)
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "time.now"))
        state.apply(ModelEvent.ToolArgumentsDelta(0, "1234"))
        state.apply(ModelEvent.ToolArgumentsDelta(0, "5"))
        state.apply(ModelEvent.ToolCallFinished(0))

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals("TOOL_ARGS_OVERFLOW", terminal.errorCode)
    }

    @Test
    fun unfinishedSiblingFailsWholeStreamBeforeAnyToolCanRun() {
        val state = ModelStreamState()
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"))
        state.apply(ModelEvent.ToolCallFinished(0))
        state.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-2"), "b"))

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals("TOOL_STREAM_TRUNCATED", terminal.errorCode)
        assertTrue(state.finishedToolCalls.size == 1)
    }

    @Test
    fun refusalTakesPrecedenceOverProviderError() {
        val state = ModelStreamState()
        state.apply(ModelEvent.Error(ModelErrorCode.AUTH, retryable = false))
        state.apply(ModelEvent.Refusal())

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals(ModelStreamState.REFUSAL, terminal.errorCode)
    }

    @Test
    fun providerErrorKeepsStableCodeAndSafeMappedLabel() {
        val state = ModelStreamState()
        state.apply(ModelEvent.Error(ModelErrorCode.RATE_LIMITED, retryable = true))

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals("RATE_LIMITED", terminal.errorCode)
    }

    @Test
    fun cancellationHasHighestTerminalPrecedence() {
        val state = ModelStreamState()
        state.apply(ModelEvent.Refusal())

        val terminal = state.terminal(cancelled = true)
        assertEquals(TurnState.CANCELLED, terminal.state)
        assertNull(terminal.errorCode)
    }

    @Test
    fun nonPositiveArgumentBudgetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ModelStreamState(maxToolArgumentsChars = 0) }
    }

    @Test
    fun duplicateIndexAndCallIdFailClosed() {
        val duplicateIndex = ModelStreamState()
        duplicateIndex.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"))
        duplicateIndex.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-2"), "b"))
        assertEquals("TOOL_STREAM_INVALID", duplicateIndex.terminal(false).errorCode)

        val duplicateId = ModelStreamState()
        duplicateId.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"))
        duplicateId.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-1"), "b"))
        assertEquals("TOOL_STREAM_INVALID", duplicateId.terminal(false).errorCode)
    }

    @Test
    fun textAndAggregateToolBudgetsFailClosed() {
        val text = ModelStreamState(maxTextChars = 2)
        text.apply(ModelEvent.TextDelta("ab"))
        text.apply(ModelEvent.TextDelta("c"))
        assertEquals("MODEL_TEXT_OVERFLOW", text.terminal(false).errorCode)
        assertEquals("ab", text.text)

        val aggregate =
            ModelStreamState(
                maxToolArgumentsChars = 4,
                maxAggregateToolArgumentsChars = 5,
            )
        aggregate.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"))
        aggregate.apply(ModelEvent.ToolArgumentsDelta(0, "123"))
        aggregate.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-2"), "b"))
        aggregate.apply(ModelEvent.ToolArgumentsDelta(1, "456"))
        assertEquals("TOOL_ARGS_OVERFLOW", aggregate.terminal(false).errorCode)
    }

    @Test
    fun tooManyToolCallsFailClosed() {
        val state = ModelStreamState(maxToolCalls = 1)
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"))
        state.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-2"), "b"))
        assertEquals("TOOL_CALL_COUNT_OVERFLOW", state.terminal(false).errorCode)
    }
}
