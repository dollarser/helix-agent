package com.helix.app.chat

import com.helix.app.runcontrol.BudgetStopReasons
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity

/** Explicit user continuation of settled ordinary work. Goal activation keeps its own path. */
internal object BudgetContinuation {
    private val settled = setOf("COMPLETED", "FAILED", "DENIED", "CANCELLED")

    fun blocksRetry(
        storage: HelixStorage,
        turn: TurnEntity,
        eligible: Boolean,
    ): Boolean =
        !eligible && turn.errorCode in BudgetStopReasons.turn && storage.goalTurnBindings.byTurn(turn.id) == null

    fun eligible(
        storage: HelixStorage,
        turn: TurnEntity,
    ): Boolean =
        turn.state == TurnState.FAILED.name && turn.errorCode in BudgetStopReasons.turn &&
            storage.goalTurnBindings.byTurn(turn.id) == null &&
            storage.turns
                .listBySession(turn.sessionId)
                .lastOrNull()
                ?.id == turn.id &&
            storage.turns.listBySession(turn.sessionId).all { prior ->
                storage.toolCalls.listByTurn(prior.id).all { it.state in settled }
            }
}
