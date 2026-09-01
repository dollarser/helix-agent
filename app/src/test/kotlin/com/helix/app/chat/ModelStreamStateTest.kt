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

        val first = state.apply(ModelEvent.TextDelta("你"), ::label)
        val second = state.apply(ModelEvent.TextDelta("好"), ::label)

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

        state.apply(ModelEvent.Usage(null, null), ::label)
        assertNull(state.usageJson)
        state.apply(ModelEvent.Usage(12, null), ::label)
        assertEquals("{\"inputTokens\":12}", state.usageJson)
        state.apply(ModelEvent.Usage(null, 7), ::label)
        assertEquals("{\"outputTokens\":7}", state.usageJson)
    }

    @Test
    fun finishedToolCallsUseProviderIndexOrderAndKeepRawArgumentFragments() {
        val state = ModelStreamState()
        state.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-b"), "b"), ::label)
        state.apply(ModelEvent.ToolArgumentsDelta(1, "{\"b\":"), ::label)
        state.apply(ModelEvent.ToolArgumentsDelta(1, "2}"), ::label)
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-a"), "a"), ::label)
        state.apply(ModelEvent.ToolArgumentsDelta(0, "{}"), ::label)
        state.apply(ModelEvent.ToolCallFinished(1), ::label)
        state.apply(ModelEvent.ToolCallFinished(0), ::label)

        assertEquals(listOf("call-a", "call-b"), state.finishedToolCalls.map { it.callId })
        assertEquals("{\"b\":2}", state.finishedToolCalls.last().arguments)
        assertEquals(TurnState.COMPLETED, state.terminal(cancelled = false).state)
    }

    @Test
    fun orphanArgumentAndFinishEventsFailClosed() {
        val state = ModelStreamState()

        state.apply(ModelEvent.ToolArgumentsDelta(3, "{}"), ::label)
        state.apply(ModelEvent.ToolCallFinished(3), ::label)

        assertTrue(state.finishedToolCalls.isEmpty())
        assertEquals(TurnState.FAILED, state.terminal(cancelled = false).state)
        assertEquals("TOOL_STREAM_INVALID", state.terminal(cancelled = false).errorCode)
    }

    @Test
    fun totalToolArgumentsOverflowFailsClosedBeforeDispatch() {
        val state = ModelStreamState(maxToolArgumentsChars = 4)
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "time.now"), ::label)
        state.apply(ModelEvent.ToolArgumentsDelta(0, "1234"), ::label)
        state.apply(ModelEvent.ToolArgumentsDelta(0, "5"), ::label)
        state.apply(ModelEvent.ToolCallFinished(0), ::label)

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals("TOOL_ARGS_OVERFLOW", terminal.errorCode)
        assertEquals("工具参数超出上限", terminal.displayLabel)
    }

    @Test
    fun unfinishedSiblingFailsWholeStreamBeforeAnyToolCanRun() {
        val state = ModelStreamState()
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"), ::label)
        state.apply(ModelEvent.ToolCallFinished(0), ::label)
        state.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-2"), "b"), ::label)

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals("TOOL_STREAM_TRUNCATED", terminal.errorCode)
        assertTrue(state.finishedToolCalls.size == 1)
    }

    @Test
    fun refusalTakesPrecedenceOverProviderError() {
        val state = ModelStreamState()
        state.apply(ModelEvent.Error(ModelErrorCode.AUTH, retryable = false), ::label)
        state.apply(ModelEvent.Refusal(), ::label)

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertNull(terminal.errorCode)
        assertEquals("模型拒绝（安全/策略）", terminal.displayLabel)
    }

    @Test
    fun providerErrorKeepsStableCodeAndSafeMappedLabel() {
        val state = ModelStreamState()
        state.apply(ModelEvent.Error(ModelErrorCode.RATE_LIMITED, retryable = true), ::label)

        val terminal = state.terminal(cancelled = false)
        assertEquals(TurnState.FAILED, terminal.state)
        assertEquals("RATE_LIMITED", terminal.errorCode)
        assertEquals("safe-RATE_LIMITED", terminal.displayLabel)
    }

    @Test
    fun cancellationHasHighestTerminalPrecedence() {
        val state = ModelStreamState()
        state.apply(ModelEvent.Refusal(), ::label)

        val terminal = state.terminal(cancelled = true)
        assertEquals(TurnState.CANCELLED, terminal.state)
        assertNull(terminal.errorCode)
        assertEquals("已停止", terminal.displayLabel)
    }

    @Test
    fun nonPositiveArgumentBudgetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ModelStreamState(maxToolArgumentsChars = 0) }
    }

    @Test
    fun duplicateIndexAndCallIdFailClosed() {
        val duplicateIndex = ModelStreamState()
        duplicateIndex.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"), ::label)
        duplicateIndex.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-2"), "b"), ::label)
        assertEquals("TOOL_STREAM_INVALID", duplicateIndex.terminal(false).errorCode)

        val duplicateId = ModelStreamState()
        duplicateId.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"), ::label)
        duplicateId.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-1"), "b"), ::label)
        assertEquals("TOOL_STREAM_INVALID", duplicateId.terminal(false).errorCode)
    }

    @Test
    fun textAndAggregateToolBudgetsFailClosed() {
        val text = ModelStreamState(maxTextChars = 2)
        text.apply(ModelEvent.TextDelta("ab"), ::label)
        text.apply(ModelEvent.TextDelta("c"), ::label)
        assertEquals("MODEL_TEXT_OVERFLOW", text.terminal(false).errorCode)
        assertEquals("ab", text.text)

        val aggregate =
            ModelStreamState(
                maxToolArgumentsChars = 4,
                maxAggregateToolArgumentsChars = 5,
            )
        aggregate.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"), ::label)
        aggregate.apply(ModelEvent.ToolArgumentsDelta(0, "123"), ::label)
        aggregate.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-2"), "b"), ::label)
        aggregate.apply(ModelEvent.ToolArgumentsDelta(1, "456"), ::label)
        assertEquals("TOOL_ARGS_OVERFLOW", aggregate.terminal(false).errorCode)
    }

    @Test
    fun tooManyToolCallsFailClosed() {
        val state = ModelStreamState(maxToolCalls = 1)
        state.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a"), ::label)
        state.apply(ModelEvent.ToolCallStarted(1, ToolCallId("call-2"), "b"), ::label)
        assertEquals("TOOL_CALL_COUNT_OVERFLOW", state.terminal(false).errorCode)
    }

    private fun label(code: ModelErrorCode) = "safe-${code.name}"
}
