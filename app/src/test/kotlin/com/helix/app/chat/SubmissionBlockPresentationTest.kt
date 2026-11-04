package com.helix.app.chat

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SubmissionBlockPresentationTest {
    private fun screen() =
        ChatScreenState(
            sessions = emptyList(),
            openSessionId = "session",
            selectedModel = null,
            messages = emptyList(),
            toolTimeline = emptyList(),
            activeTurn = TurnUi("new", TurnState.WAITING_MODEL, null, null, false),
            pendingDisclosure = null,
            blockedReason = "old failure",
            retryTargetTurnId = null,
            turns = listOf(TurnUi("old", TurnState.FAILED, null, "historical failure", true)),
            blockedReasonEpoch = 3,
        )

    @Test fun acceptedQuestionClearsPreviousBannerWithoutChangingHistoricalFailure() {
        val next = clearAcceptedSubmissionBlock(screen(), "session", 3)
        assertNull(next.blockedReason)
        assertEquals("historical failure", next.turns.single().errorLabel)
        assertEquals(TurnState.WAITING_MODEL, next.activeTurn?.state)
    }

    @Test fun laterFailureEvenWithSameTextAndAnotherSessionCannotBeClearedByAnOldAcceptance() {
        val later = screen().copy(blockedReasonEpoch = 4)
        assertSame(later, clearAcceptedSubmissionBlock(later, "session", 3))
        val other = screen().copy(openSessionId = "other")
        assertSame(other, clearAcceptedSubmissionBlock(other, "session", 3))
    }
}
