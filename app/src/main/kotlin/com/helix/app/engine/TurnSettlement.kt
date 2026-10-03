package com.helix.app.engine

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.GoalRunSettlement
import com.helix.core.agent.AgentTurnStore
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.StoredTurnCommitState
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.TerminalCommitResult
import com.helix.core.agent.TurnCommitDecision
import com.helix.core.agent.TurnCommitExpectation
import com.helix.core.agent.TurnCommitPolicy
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.agent.requireOutcome
import com.helix.core.model.Clock
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Engine-owned Room adapter. Core sees one command and no database/entity/transaction callbacks. */
internal class TurnSettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : AgentTurnStore {
    // The Engine invokes this on its IO scope. No suspension is inserted inside the bounded local
    // commit: even a cancelled live driver must durably finish its cancellation/uncertainty facts.
    override suspend fun commitTerminal(command: TerminalCommitCommand): TerminalCommitResult = commitBlocking(command)

    private fun commitBlocking(command: TerminalCommitCommand): TerminalCommitResult {
        var result: TerminalCommitResult? = null
        storage.withTransaction { result = commitInTransaction(command) }
        return requireNotNull(result)
    }

    /** Existing fixture seam; production calls the AgentTurnStore port. */
    fun settle(
        checkpoint: TurnTerminalCheckpoint,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal = commitBlocking(TerminalCommitCommand(checkpoint, outcome)).requireOutcome()

    /** Parent transaction owns Steer-vs-final-answer linearization; never start a separate commit. */
    fun settleInTransaction(
        checkpoint: TurnTerminalCheckpoint,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal = commitInTransaction(TerminalCommitCommand(checkpoint, outcome)).requireOutcome()

    private fun commitInTransaction(command: TerminalCommitCommand): TerminalCommitResult {
        val checkpoint = command.checkpoint
        val durable = storage.turns.find(checkpoint.turnId)
        val model = storage.modelCalls.find(checkpoint.modelCallId)
        val actual =
            durable?.let {
                StoredTurnCommitState(
                    sessionId = it.sessionId,
                    turnId = it.id,
                    phase = TurnState.valueOf(it.state),
                    modelStep = it.stepCount,
                    modelCallTurnId = model?.turnId,
                    modelCallState = model?.state,
                    errorCode = it.errorCode,
                    hasOtherRunningModelCall = storage.modelCalls.hasOtherRunning(it.id, checkpoint.modelCallId),
                    modelCallId = model?.id,
                )
            }
        val expected =
            TurnCommitExpectation(
                checkpoint.sessionId,
                checkpoint.turnId,
                checkpoint.phase,
                checkpoint.modelCallId,
                checkpoint.modelStep,
                checkpoint.modelCallClosed,
            )
        return when (val decision = TurnCommitPolicy.terminal(expected, actual)) {
            TurnCommitDecision.Apply -> {
                TerminalCommitResult.Applied(persist(command, requireNotNull(durable)))
            }

            is TurnCommitDecision.AlreadyApplied -> {
                TerminalCommitResult.AlreadyApplied(ModelStreamTerminal(decision.phase, decision.errorCode))
            }

            is TurnCommitDecision.Conflict -> {
                TerminalCommitResult.Conflict(decision.reason)
            }

            is TurnCommitDecision.Unavailable -> {
                TerminalCommitResult.Unavailable(decision.reason)
            }
        }
    }

    private fun persist(
        command: TerminalCommitCommand,
        durable: com.helix.core.storage.entity.TurnEntity,
    ): ModelStreamTerminal {
        val checkpoint = command.checkpoint
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
        val settled = TurnCommitPolicy.outcome(command.outcome, TurnState.valueOf(turn.state))
        if (settled.state == TurnState.CANCELLED && turn.state != TurnState.CANCELLING.name) {
            turn = storage.turns.updateState(turn, TurnState.CANCELLING, checkpoint.modelStep, null, null)
        }
        storage.turns.updateState(
            turn,
            settled.state,
            checkpoint.modelStep,
            clock.now().toEpochMilli().coerceAtLeast(turn.startedAt),
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
        internal fun settleOutcomeAfterCancelling(
            outcome: ModelStreamTerminal,
            persistedState: String,
        ): ModelStreamTerminal = TurnCommitPolicy.outcome(outcome, TurnState.valueOf(persistedState))

        private fun callState(turn: TurnState): String =
            when (turn) {
                TurnState.COMPLETED -> "COMPLETED"
                TurnState.CANCELLED -> "CANCELLED"
                else -> "FAILED"
            }
    }
}
