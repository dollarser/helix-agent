package com.helix.app.application.tools

import com.helix.core.model.BoundToolResult
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSettlementTest {
    @Test fun successCommitsCompleteContentBeforeReadingProjection() {
        val store = RecordingStore()
        val payload = "x".repeat(700)
        settlement(store).settleToolCall(
            "row",
            "call",
            "read",
            ToolDispatchOutcome.Succeeded(BoundToolResult(payload, Sha256("a".repeat(64)), false, 1)),
        )
        val command = requireNotNull(store.command)
        assertEquals("row", command.rowId)
        assertEquals(ToolCallState.COMPLETED, command.state)
        assertEquals("SUCCEEDED", command.status)
        assertEquals(payload, command.content)
        assertEquals("x".repeat(500) + "…", command.summary)
        assertTrue(command.verified)
        assertEquals(listOf("commit", "read", "publish"), store.events)
    }

    @Test fun unknownEffectsRemainReviewRequiredInCompleteCommit() {
        val store = RecordingStore()
        settlement(store).settleToolCall(
            "row",
            "call",
            "write",
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TOOL_FAILED,
                "lost response",
                requiresReview = true,
            ),
            sideEffectUnknown = true,
        )
        assertEquals(ToolCallState.NEEDS_REVIEW, store.command?.state)
        assertEquals("NEEDS_REVIEW", store.command?.status)
        assertFalse(requireNotNull(store.command).verified)
        assertTrue(requireNotNull(store.command?.content).contains("lost response"))
    }

    @Test fun commitFailurePropagatesWithoutReadingOrPublishingProjection() {
        val store = RecordingStore()
        val failure = IllegalStateException("commit failed")
        store.failure = failure
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                settlement(store).settleToolCall("row", "call", "read", ToolDispatchOutcome.Cancelled)
            }
        assertSame(failure, thrown)
        assertEquals(listOf("commit"), store.events)
    }

    @Test fun rejectionKeepsOriginalIdentityAndDecisionInOneCommand() {
        val store = RecordingStore()
        val origin = ToolSettlementOrigin("model", 2)
        val result =
            settlement(store).persistPreDispatchDenied(
                "turn",
                "call",
                "bad name",
                "[]",
                "unknown",
                DispatchOutcomeCode.UNKNOWN_TOOL,
                "bad binding",
                PreDispatchDenialKind.FRAMEWORK_REJECTED,
                "original intent",
                origin,
            )
        assertEquals(DispatchOutcomeCode.UNKNOWN_TOOL, result.code)
        assertEquals(
            PreDispatchDenialCommand(
                "turn",
                "call",
                "bad name",
                "[]",
                "unknown",
                DispatchOutcomeCode.UNKNOWN_TOOL,
                "bad binding",
                PreDispatchDenialKind.FRAMEWORK_REJECTED,
                "original intent",
                origin,
            ),
            store.denied,
        )
        assertEquals(listOf("deny", "read", "publish"), store.events)
    }

    private fun settlement(store: RecordingStore) =
        ToolSettlement(
            store,
            ToolPresentationSink { store.events += "publish" },
            { null },
        )

    private class RecordingStore : ToolSettlementStore {
        val events = mutableListOf<String>()
        var command: ToolSettlementCommand? = null
        var denied: PreDispatchDenialCommand? = null
        var failure: RuntimeException? = null

        override fun persist(command: ToolSettlementCommand) {
            events += "commit"
            failure?.let { throw it }
            this.command = command
        }

        override fun persistDenied(command: PreDispatchDenialCommand) {
            events += "deny"
            denied = command
        }

        override fun callFact(
            rowId: String,
            callId: String,
            name: String,
        ): ToolCallFact {
            events += "read"
            return ToolCallFact("session", "turn", callId, name, "{}", null)
        }
    }
}
