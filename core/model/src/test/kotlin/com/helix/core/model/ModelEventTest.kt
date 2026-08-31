package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelEventTest {
    @Test
    fun textDeltasAreBoundedButTextual() {
        val event = ModelEvent.TextDelta("hello\nworld\t\t```")
        assertEquals("hello\nworld\t\t```", event.text)
        assertThrows<IllegalArgumentException>("empty delta must be rejected") { ModelEvent.TextDelta("") }
        assertThrows<IllegalArgumentException>("NUL delta must be rejected") { ModelEvent.TextDelta("a\u0000") }
        assertThrows<IllegalArgumentException>("oversize delta must be rejected") {
            ModelEvent.TextDelta("x".repeat(ModelEvent.MAX_DELTA_LENGTH + 1))
        }
    }

    @Test
    fun reasoningDeltasAreBoundedButTextual() {
        assertEquals("thinking…", ModelEvent.ReasoningDelta("thinking…").text)
        assertThrows<IllegalArgumentException>("empty reasoning delta must be rejected") {
            ModelEvent.ReasoningDelta("")
        }
        assertThrows<IllegalArgumentException>("oversize reasoning delta must be rejected") {
            ModelEvent.ReasoningDelta("x".repeat(ModelEvent.MAX_DELTA_LENGTH + 1))
        }
    }

    @Test
    fun toolCallStartedRulesAreEnforced() {
        val event = ModelEvent.ToolCallStarted(0, ToolCallId("call_abc123"), "read")
        assertEquals(0, event.index)
        assertEquals(ToolCallId("call_abc123"), event.id)
        assertThrows<IllegalArgumentException>("negative index must be rejected") {
            ModelEvent.ToolCallStarted(-1, ToolCallId("call-1"), "read")
        }
        assertThrows<IllegalArgumentException>("bad name must be rejected") {
            ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "read file")
        }
        assertThrows<IllegalArgumentException>("oversize name must be rejected") {
            ModelEvent.ToolCallStarted(
                0,
                ToolCallId("call-1"),
                "x".repeat(ModelEvent.MAX_TOOL_NAME_LENGTH + 1),
            )
        }
        assertThrows<IllegalArgumentException>("control char name must be rejected") {
            ModelEvent.ToolCallStarted(0, ToolCallId("call-1"), "a\u0001b")
        }
        // A vendor id outside the ToolCallId charset cannot produce a partial event:
        // construction fails before any event exists (the adapter turns this into
        // Error(PROTOCOL, retryable=false)).
        assertThrows<IllegalArgumentException>("charset-violating vendor id must be rejected") {
            ModelEvent.ToolCallStarted(0, ToolCallId("call id with space"), "read")
        }
    }

    @Test
    fun argumentsDeltasAreBoundedFragments() {
        val event = ModelEvent.ToolArgumentsDelta(0, """{"path":"a""")
        assertEquals("""{"path":"a""", event.jsonFragment)
        assertThrows<IllegalArgumentException>("negative index must be rejected") {
            ModelEvent.ToolArgumentsDelta(-1, "{}")
        }
        assertThrows<IllegalArgumentException>("empty fragment must be rejected") {
            ModelEvent.ToolArgumentsDelta(0, "")
        }
        assertThrows<IllegalArgumentException>("NUL fragment must be rejected") {
            ModelEvent.ToolArgumentsDelta(0, "a\u0000")
        }
        assertThrows<IllegalArgumentException>("oversize fragment must be rejected") {
            ModelEvent.ToolArgumentsDelta(0, "x".repeat(ModelEvent.MAX_DELTA_LENGTH + 1))
        }
    }

    @Test
    fun toolCallFinishedRulesAreEnforced() {
        assertEquals(2, ModelEvent.ToolCallFinished(2).index)
        assertThrows<IllegalArgumentException>("negative index must be rejected") {
            ModelEvent.ToolCallFinished(-1)
        }
    }

    @Test
    fun usageAllowsUnreportedFiguresButNotNegative() {
        assertEquals(null, ModelEvent.Usage(null, null).inputTokens)
        assertEquals(0L, ModelEvent.Usage(0, 0).inputTokens)
        assertThrows<IllegalArgumentException>("negative input must be rejected") {
            ModelEvent.Usage(-1, 0)
        }
        assertThrows<IllegalArgumentException>("negative output must be rejected") {
            ModelEvent.Usage(0, -1)
        }
    }

    @Test
    fun refusalReasonIsSafeAndOptional() {
        assertEquals(null, ModelEvent.Refusal().safeReason)
        assertEquals("policy", ModelEvent.Refusal("policy").safeReason)
        assertThrows<IllegalArgumentException>("blank reason must be rejected") {
            ModelEvent.Refusal("  ")
        }
        assertThrows<IllegalArgumentException>("oversize reason must be rejected") {
            ModelEvent.Refusal("x".repeat(ModelEvent.MAX_SAFE_REASON_LENGTH + 1))
        }
        assertThrows<IllegalArgumentException>("control char reason must be rejected") {
            ModelEvent.Refusal("a\u0001b")
        }
    }

    @Test
    fun errorCarriesCodeAndRetryability() {
        // Closed set: the eight stream failure classes, nothing else.
        assertEquals(
            listOf(
                "TRANSPORT",
                "TIMEOUT",
                "HTTP_ERROR",
                "AUTH",
                "RATE_LIMITED",
                "SERVER_ERROR",
                "PROTOCOL",
                "CONTENT_FILTER",
            ),
            ModelErrorCode.values().map { it.name },
        )
        assertEquals(true, ModelEvent.Error(ModelErrorCode.RATE_LIMITED, true).retryable)
        assertEquals(false, ModelEvent.Error(ModelErrorCode.AUTH, false).retryable)
        assertEquals(ModelErrorCode.PROTOCOL, ModelEvent.Error(ModelErrorCode.PROTOCOL, false).code)
    }

    @Test
    fun completedReasonIsNormalizedAndOptional() {
        assertEquals(null, ModelEvent.Completed().finishReason)
        assertEquals("stop", ModelEvent.Completed("stop").finishReason)
        assertEquals("tool_calls", ModelEvent.Completed("tool_calls").finishReason)
        assertThrows<IllegalArgumentException>("whitespace reason must be rejected") {
            ModelEvent.Completed("to o")
        }
        assertThrows<IllegalArgumentException>("oversize reason must be rejected") {
            ModelEvent.Completed("x".repeat(ModelEvent.MAX_FINISH_REASON_LENGTH + 1))
        }
        assertThrows<IllegalArgumentException>("blank reason must be rejected") {
            ModelEvent.Completed("  ")
        }
    }
}
