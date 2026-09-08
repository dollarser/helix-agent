package com.helix.app.chat

import com.helix.core.model.TurnState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiModelsTest {
    @Test fun interruptedHistoryHasNoLiveSend() {
        assertFalse(screen(TurnState.INTERRUPTED).isSending)
    }

    @Test fun pendingApprovalAndCancellationStillBelongToALiveSend() {
        assertTrue(screen(TurnState.WAITING_APPROVAL).isSending)
        assertTrue(screen(TurnState.CANCELLING).isSending)
        assertTrue(screen(TurnState.RUNNING_TOOL).isSending)
    }

    @Test fun terminalTurnsHaveNoLiveSend() {
        assertFalse(screen(TurnState.COMPLETED).isSending)
        assertFalse(screen(TurnState.FAILED).isSending)
        assertFalse(screen(TurnState.CANCELLED).isSending)
    }

    private fun screen(state: TurnState) =
        ChatScreenState(
            sessions = emptyList(),
            openSessionId = "session",
            badge = null,
            messages = emptyList(),
            toolTimeline = emptyList(),
            activeTurn = TurnUi("turn", state, null, null, false),
            pendingDisclosure = null,
            blockedReason = null,
            retryTargetTurnId = null,
        )
}
