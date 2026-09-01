package com.helix.app.chat

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TurnCoordinatorTest {
    @Test
    fun laterModelStepOwnsItsOwnStreamCheckpoint() {
        val runtime = BatchTurnRuntime("model-1")
        runtime.beginModelStream().apply(
            com.helix.core.model.ModelEvent
                .TextDelta("first"),
            ::label,
        )
        runtime.beginBatch(listOf("tool-1"))
        runtime.markModelCallClosed()
        runtime.settleCall("tool-1", sideEffectUnknown = false)
        runtime.advanceModelCall("model-2")

        val second = runtime.beginModelStream()
        second.apply(
            com.helix.core.model.ModelEvent
                .TextDelta("partial-second"),
            ::label,
        )

        assertEquals("model-2", runtime.snapshot().modelCallId)
        assertEquals(2, runtime.snapshot().modelStep)
        assertEquals("partial-second", runtime.currentStream().text)
        assertEquals(false, runtime.snapshot().modelCallClosed)
    }

    @Test
    fun batchTracksEveryCallAndRequiresAllKnownSettlements() {
        val runtime = BatchTurnRuntime("model-1")
        runtime.beginModelStream()
        runtime.beginBatch(listOf("tool-a", "tool-b", "tool-c"))
        runtime.markModelCallClosed()
        runtime.settleCall("tool-b", sideEffectUnknown = false)
        runtime.settleCall("tool-a", sideEffectUnknown = false)
        runtime.settleCall("tool-c", sideEffectUnknown = true)

        assertEquals(
            mapOf(
                "tool-a" to BatchCallResolution.SETTLED,
                "tool-b" to BatchCallResolution.SETTLED,
                "tool-c" to BatchCallResolution.UNKNOWN,
            ),
            runtime.snapshot().batchCalls,
        )
        assertThrows(IllegalArgumentException::class.java) { runtime.advanceModelCall("model-2") }
    }

    @Test
    fun duplicateBatchIdentityFailsBeforeChangingPhase() {
        val runtime = BatchTurnRuntime("model-1")
        runtime.beginModelStream()

        assertThrows(IllegalArgumentException::class.java) {
            runtime.beginBatch(listOf("same", "same"))
        }
        assertEquals(TurnState.RECEIVING_MODEL, runtime.snapshot().phase)
    }

    @Test
    fun terminalCheckpointKeepsCurrentLaterCall() {
        val runtime = BatchTurnRuntime("model-1")
        runtime.beginModelStream()
        runtime.beginBatch(listOf("tool-1"))
        runtime.markModelCallClosed()
        runtime.settleCall("tool-1", sideEffectUnknown = false)
        runtime.advanceModelCall("model-2")
        runtime.beginModelStream()

        runtime.terminalize(TurnState.FAILED)

        assertEquals("model-2", runtime.snapshot().modelCallId)
        assertEquals(TurnState.FAILED, runtime.snapshot().phase)
    }

    private fun label(code: com.helix.core.model.ModelErrorCode) = code.name
}
