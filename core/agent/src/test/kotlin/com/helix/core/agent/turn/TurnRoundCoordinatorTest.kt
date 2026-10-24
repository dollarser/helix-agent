package com.helix.core.agent.turn

import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRoundCoordinatorTest {
    @Test fun failedBeginCommitDoesNotPublishReceivingOrSummaryState() {
        val store = FakeStore().apply { fail = "begin" }
        val coordinator = coordinator(store)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
        }
        assertEquals(TurnState.WAITING_MODEL, coordinator.snapshot().phase)
        assertFalse(coordinator.terminalCheckpoint().summaryStream)
        store.fail = null
        kotlinx.coroutines.runBlocking { coordinator.beginModelStream(compacting = true) }
        assertTrue(coordinator.terminalCheckpoint().summaryStream)
    }

    @Test fun failedModelToolCommitDoesNotCloseTheLiveModelCall() {
        val store = FakeStore()
        val coordinator = coordinator(store)
        kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }.apply(ModelEvent.TextDelta("progress"))
        kotlinx.coroutines.runBlocking { coordinator.beginToolBatch(listOf("first", "second")) }
        store.fail = "tool-step"
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.commitModelToolStep("[]") }
        }
        assertFalse(coordinator.snapshot().modelCallClosed)
        assertEquals("progress", coordinator.currentStream().text)
        store.fail = null
        kotlinx.coroutines.runBlocking { coordinator.commitModelToolStep("[]") }
        assertTrue(coordinator.snapshot().modelCallClosed)
    }

    @Test fun persistedCancellationKeepsCollectedResultsAndNeverAdvancesModelIdentity() {
        val store = FakeStore().apply { continueAfterResults = false }
        val coordinator = coordinator(store)
        kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
        kotlinx.coroutines.runBlocking { coordinator.beginToolBatch(listOf("first")) }
        kotlinx.coroutines.runBlocking { coordinator.commitModelToolStep("[]") }
        coordinator.settleBatchCall("first", false)
        val messages = listOf(TurnMessageDraft(ModelRole.TOOL, "tool_result", "original result"))
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.openNextModelCall(messages, "next") }
        }
        assertEquals(messages, store.collected)
        assertEquals("first-model", coordinator.snapshot().modelCallId)
        assertEquals(1, coordinator.snapshot().modelStep)
        assertTrue(coordinator.snapshot().modelCallClosed)
    }

    @Test fun unknownSiblingCannotOpenNewModelCallAndReviewKeepsOriginalOrder() {
        val store = FakeStore()
        val coordinator = coordinator(store)
        kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
        kotlinx.coroutines.runBlocking { coordinator.beginToolBatch(listOf("first", "second")) }
        kotlinx.coroutines.runBlocking { coordinator.commitModelToolStep("[]") }
        coordinator.settleBatchCall("second", true)
        assertEquals(null, coordinator.settledReviewCallIds())
        coordinator.settleBatchCall("first", false)
        assertEquals(listOf("second"), coordinator.settledReviewCallIds())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.openNextModelCall(emptyList(), "next") }
        }
        assertFalse(store.events.contains("next"))
        val checkpoint = coordinator.reviewCheckpoint(listOf("second"))
        assertEquals(listOf("first", "second"), checkpoint.batchCalls.keys.toList())
        coordinator.markRuntimeParked(checkpoint.reviewCallIds)
        assertEquals(TurnState.NEEDS_REVIEW, coordinator.snapshot().phase)
    }

    @Test fun responseReceiptsAloneAdvanceTheSameCoordinator() {
        val store = FakeStore()
        val coordinator = coordinator(store)
        kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }.apply(ModelEvent.TextDelta("answer"))
        store.response = ResponseCommitReceipt(ResponseInputBoundary.RECHECK)
        assertEquals(ResponseInputBoundary.RECHECK, coordinator.completeResponseOrContinue(null, "next"))
        assertEquals("first-model", coordinator.snapshot().modelCallId)
        store.fail = "response"
        assertThrows(IllegalStateException::class.java) { coordinator.completeResponseOrContinue(null, "next") }
        assertEquals(TurnState.RECEIVING_MODEL, coordinator.snapshot().phase)
        store.fail = null
        store.response = ResponseCommitReceipt(ResponseInputBoundary.CONTINUED)
        coordinator.completeResponseOrContinue(null, "next")
        assertEquals("next", coordinator.snapshot().modelCallId)
        kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
        store.response =
            ResponseCommitReceipt(ResponseInputBoundary.TERMINAL, ModelStreamTerminal(TurnState.COMPLETED, null))
        coordinator.completeResponseOrContinue(null, "unused")
        assertEquals(TurnState.COMPLETED, coordinator.snapshot().phase)
        assertEquals("next", coordinator.snapshot().modelCallId)
    }

    private fun coordinator(store: TurnRoundStore) = TurnRoundCoordinator("session", "turn", "first-model", store)

    private class FakeStore : TurnRoundStore {
        var fail: String? = null
        var continueAfterResults = true
        var collected = emptyList<TurnMessageDraft>()
        var response = ResponseCommitReceipt(ResponseInputBoundary.RECHECK)
        val events = mutableListOf<String>()

        private fun commit(event: String) {
            check(fail != event) { "injected transaction failure" }
            events += event
        }

        override fun beginModelStream(checkpoint: TurnTerminalCheckpoint) = commit("begin")

        override fun beginToolBatch(checkpoint: TurnTerminalCheckpoint) = commit("batch")

        override fun commitModelToolStep(
            checkpoint: TurnTerminalCheckpoint,
            toolCallsJson: String,
        ) = commit("tool-step")

        override fun retryEmptyModelStream(
            checkpoint: TurnTerminalCheckpoint,
            nextModelCallId: String,
        ) = commit("retry")

        override fun openNextModelCall(
            checkpoint: TurnTerminalCheckpoint,
            messages: List<TurnMessageDraft>,
            nextModelCallId: String,
        ): ToolResultsCommitReceipt {
            commit("next")
            collected = messages
            return if (continueAfterResults) {
                ToolResultsCommitReceipt.NEXT_MODEL_OPENED
            } else {
                ToolResultsCommitReceipt.RECORDED_CANCELLED
            }
        }

        override fun appendSteeringBeforeRequest(
            checkpoint: TurnTerminalCheckpoint,
            draft: TurnSteeringInput,
        ) = false

        override fun completeResponse(
            checkpoint: TurnTerminalCheckpoint,
            draft: TurnSteeringInput?,
            nextModelCallId: String,
        ): ResponseCommitReceipt {
            commit("response")
            return response
        }

        override fun recordDiagnostic(
            modelCallId: String,
            type: String,
            payload: String,
        ) = Unit

        override fun recordPromptSnapshot(
            modelCallId: String,
            snapshot: PromptSnapshot?,
            summary: Boolean,
        ) = Unit

        override fun recordRequestManifest(
            modelCallId: String,
            manifestJson: String?,
        ) = Unit

        override fun recordInputRequestStarted(
            checkpoint: TurnTerminalCheckpoint,
            messageIds: Set<String>,
        ) = Unit

        override fun commitCompaction(
            checkpoint: TurnTerminalCheckpoint,
            plan: ContextCompactionPlan,
            nextModelCallId: String?,
            notice: String?,
            failureReason: String?,
            saveSummary: Boolean,
        ) = commit("compaction")
    }
}
