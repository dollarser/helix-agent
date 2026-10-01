package com.helix.app.chat

import com.helix.app.engine.TurnObservation
import com.helix.app.engine.TurnRuntimeView
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.CancelResult
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.agent.TurnSnapshot
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Thin app adapter from the framework-free [AgentRuntime] contract to the Chat command seam and
 * Engine-owned runtime view. It owns no Turn execution state.
 */
internal class AppAgentRuntime(
    private val startTurn: suspend (SubmitTurnCommand, RunControlConfig) -> String?,
    private val cancelTurn: suspend (String) -> TurnCancelOutcome,
    private val runtime: TurnRuntimeView,
) : AgentRuntime {
    override suspend fun submit(command: SubmitTurnCommand): TurnId {
        val control =
            RunControlConfig(
                command.mode,
                command.chatToolsEnabled,
                command.budgets,
                command.reasoning,
                command.goalBudgets ?: com.helix.core.agent.GoalBudgetDefaults.VALUE,
            )
        val turnId = startTurn(command, control) ?: throw TurnStartBlocked()
        return TurnId(turnId)
    }

    override suspend fun cancel(turnId: TurnId): CancelResult {
        val phase = runtime.persistedPhase(turnId.value)
        if (phase == null) return CancelResult.NotFound
        return when (val outcome = cancelTurn(turnId.value)) {
            is TurnCancelOutcome.AlreadyTerminal -> CancelResult.AlreadyTerminal(outcome.phase)
            TurnCancelOutcome.StoppedLive -> CancelResult.StopAccepted
            TurnCancelOutcome.ReviewRequired -> CancelResult.ReviewRequired
        }
    }

    override fun observe(turnId: TurnId): Flow<TurnSnapshot> =
        flow {
            var sawDurableStop = false
            runtime
                .observe(turnId.value)
                .map { observation -> liveSnapshot(turnId, observation) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot.isTerminal || snapshot.phase in PARKED_PHASES) sawDurableStop = true
                    emit(snapshot)
                }
            // The live flow has now ended: the turn terminalized (its live frames completed the
            // flow) or it was never live (an empty flow — a turn that ended, or never started,
            // before we subscribed). If we did not already observe the terminal — including the
            // case where a back-pressured live stream dropped it — project the turn's persisted
            // state so the observer still lands on the turn's real phase. A turn that does not
            // exist yields no persisted phase and ends the stream empty.
            if (!sawDurableStop) {
                runtime.persistedPhase(turnId.value)?.let { phase -> emit(persistedSnapshot(turnId, phase)) }
            }
        }

    /** A live frame for this turn, projected onto a [TurnSnapshot] (streaming text carried). */
    private fun liveSnapshot(
        turnId: TurnId,
        observation: TurnObservation,
    ): TurnSnapshot =
        TurnSnapshot(
            turnId = turnId,
            phase = observation.state,
            assistantText = textFor(turnId, observation.state, observation.streamingText),
            errorLabel = observation.errorLabel,
            retryable = observation.retryable,
        )

    /** The turn's persisted phase (a late subscriber, or a turn that ended before we subscribed). */
    private fun persistedSnapshot(
        turnId: TurnId,
        phase: TurnState,
    ): TurnSnapshot =
        TurnSnapshot(
            turnId = turnId,
            phase = phase,
            assistantText = textFor(turnId, phase, null),
            errorLabel = null,
            retryable = phase == TurnState.FAILED,
        )

    /** The terminal frame carries the turn's persisted text; a live frame carries its streaming text. */
    private fun textFor(
        turnId: TurnId,
        phase: TurnState,
        liveText: String?,
    ): String? = if (phase.isTerminal) runtime.persistedAssistantText(turnId.value) ?: liveText else liveText

    private companion object {
        val PARKED_PHASES = setOf(TurnState.NEEDS_REVIEW, TurnState.INTERRUPTED)
    }
}

internal class TurnStartBlocked(
    message: String = "the turn could not start; its session refused the start",
) : RuntimeException(message)

internal sealed interface TurnCancelOutcome {
    data class AlreadyTerminal(
        val phase: TurnState,
    ) : TurnCancelOutcome

    data object StoppedLive : TurnCancelOutcome

    data object ReviewRequired : TurnCancelOutcome
}
