package com.helix.app.engine

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.GoalRunSettlement
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnTerminalCheckpoint
import com.helix.core.model.Clock
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Engine-owned final durable settlement for one live Turn. */
internal class TurnSettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    /**
     * Commits assistant text, Turn terminal and an open ModelCall terminal in one durable boundary.
     * The returned outcome is the durable conclusion, including cancellation precedence over a
     * late model success/failure.
     */
    fun settle(
        checkpoint: TurnTerminalCheckpoint,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal {
        var settled: ModelStreamTerminal? = null
        storage.withTransaction { settled = settleInTransaction(checkpoint, outcome) }
        return requireNotNull(settled)
    }

    /** Caller already owns the Room transaction (Steer-vs-final-answer linearization). */
    fun settleInTransaction(
        checkpoint: TurnTerminalCheckpoint,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal {
        val durable = storage.turns.resolve(checkpoint.turnId)
        if (TurnState.valueOf(durable.state).isTerminal) {
            return ModelStreamTerminal(TurnState.valueOf(durable.state), durable.errorCode)
        }
        if (!checkpoint.modelCallClosed && !checkpoint.summaryStream && checkpoint.assistantText.isNotBlank()) {
            storage.messages.append(
                idGenerator(),
                checkpoint.sessionId,
                checkpoint.turnId,
                ModelRole.ASSISTANT.name,
                ChatHistoryBuilder.KIND_TEXT,
                checkpoint.assistantText,
            )
        }
        var turn = durable
        val settled = settleOutcomeAfterCancelling(outcome, turn.state)
        if (settled.state == TurnState.CANCELLED && turn.state != TurnState.CANCELLING.name) {
            turn = storage.turns.updateState(turn, TurnState.CANCELLING, checkpoint.modelStep, null, null)
        }
        storage.turns.updateState(
            turn,
            settled.state,
            checkpoint.modelStep,
            clock.now().toEpochMilli(),
            settled.errorCode,
        )
        if (!checkpoint.modelCallClosed) {
            storage.modelCalls.update(
                storage.modelCalls.resolve(checkpoint.modelCallId),
                callState(settled.state),
                checkpoint.usageJson,
                null,
            )
        }
        GoalRunSettlement(storage, clock, idGenerator).settle(checkpoint.turnId)
        return settled
    }

    companion object {
        /** A durable stop intent wins over a late success/failure from the model boundary. */
        internal fun settleOutcomeAfterCancelling(
            outcome: ModelStreamTerminal,
            persistedState: String,
        ): ModelStreamTerminal =
            if (persistedState == TurnState.CANCELLING.name && outcome.state != TurnState.CANCELLED) {
                outcome.copy(state = TurnState.CANCELLED)
            } else {
                outcome
            }

        private fun callState(turn: TurnState): String =
            when (turn) {
                TurnState.COMPLETED -> "COMPLETED"
                TurnState.CANCELLED -> "CANCELLED"
                else -> "FAILED"
            }
    }
}
