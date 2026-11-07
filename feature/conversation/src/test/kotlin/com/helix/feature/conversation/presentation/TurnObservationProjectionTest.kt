package com.helix.feature.conversation.presentation

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TurnObservationProjectionTest {
    private fun screen(turn: TurnUi? = null) =
        ChatScreenState(emptyList(), "s", null, emptyList(), emptyList(), turn, null, null, null)

    private fun turn(
        id: String,
        state: TurnState = TurnState.RECEIVING_MODEL,
    ) = TurnUi(id, state, "chunk", null, false)

    @Test fun otherSessionStreamCannotReachVisibleConversation() {
        val current = screen(turn("old"))
        assertSame(
            current,
            applyTurnObservation(current, "old", "other", "old", TurnState.RECEIVING_MODEL, 1, turn("old")),
        )
    }

    @Test fun newerTurnPublishedDuringReadWins() {
        val current = screen(turn("new"))
        assertSame(current, applyTurnObservation(current, "old", "s", "old", TurnState.RECEIVING_MODEL, 1, turn("old")))
    }

    @Test fun oldTurnIsNotLatestCannotReplaceSameSession() {
        val current = screen(turn("new"))
        assertSame(current, applyTurnObservation(current, "new", "s", "new", TurnState.RECEIVING_MODEL, 1, turn("old")))
    }

    @Test fun lateTextCannotResurrectDurablySettledTurn() {
        val current = screen(turn("t", TurnState.FAILED))
        assertSame(current, applyTurnObservation(current, "t", "s", "t", TurnState.FAILED, 1, turn("t")))
    }

    @Test fun newlyAdmittedTurnCanReplacePriorVisibleTurn() {
        val current = screen(turn("old", TurnState.FAILED))
        assertEquals(
            "new",
            applyTurnObservation(current, "old", "s", "new", TurnState.WAITING_MODEL, 2, turn("new")).activeTurn?.id,
        )
    }
}
