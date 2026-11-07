package com.helix.feature.conversation.presentation

import com.helix.core.model.TurnState

/** A late stream or terminal callback cannot replace a newer visible Turn or resurrect settled text. */
fun applyTurnObservation(
    current: ChatScreenState,
    expectedTurnId: String?,
    sessionId: String,
    visibleTurnId: String,
    persistedState: TurnState,
    modelStep: Int,
    observed: TurnUi,
): ChatScreenState {
    if (current.openSessionId != sessionId || observed.id != visibleTurnId ||
        current.activeTurn?.id != expectedTurnId
    ) {
        return current
    }
    val previous = current.activeTurn
    val contradictsSettlement = persistedState.isTerminal && observed.state != persistedState
    val resurrectsPrevious = previous?.id == observed.id && previous.state.isTerminal && !observed.state.isTerminal
    return if (contradictsSettlement || resurrectsPrevious) {
        current
    } else {
        current.copy(activeTurn = observed.copy(modelStep = modelStep))
    }
}
