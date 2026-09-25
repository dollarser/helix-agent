package com.helix.app.chat

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.CancelResult
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.agent.TurnSnapshot
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The app-layer [AgentRuntime] (research doc section 34; HX2-01): the production turn path exposed
 * through the framework-free core contract. Every entry point (Chat / Goal / Share / Voice /
 * Widget / Channel) drives a turn through this, and this delegates to the existing, proven turn
 * machinery via [AgentTurnHost] — never reaching into the model provider or the tool pipeline.
 *
 * [observe] streams ANY live turn independently of the open session. The live flow closes at a
 * terminal or durable parked phase (NEEDS_REVIEW / INTERRUPTED); a subscriber that joins after
 * that boundary is projected from persisted state instead of reviving a live flow.
 */
internal class AppAgentRuntime(
    private val host: AgentTurnHost,
) : AgentRuntime {
    override suspend fun submit(command: SubmitTurnCommand): TurnId {
        val turnId =
            host.startTurn(
                sessionId = command.session.value,
                clientRequestId = command.clientRequestId,
                revisedMessageId = command.revisedMessageId,
                regenerateMessageId = command.regenerateMessageId,
                text = command.text,
                providerId = command.providerId.value,
                retryTurnId = command.retryTurnId?.value,
                goalId = command.goalId?.value,
                attachments = command.attachments,
                continuousGoal = command.continuousGoal,
                goalContinuation = command.goalContinuation,
                directUserRequest = command.directUserRequest,
                control =
                    RunControlConfig(
                        command.mode,
                        command.chatToolsEnabled,
                        command.budgets,
                        command.reasoning,
                        command.goalBudgets ?: com.helix.app.runcontrol.GoalBudgetDefaults.VALUE,
                    ),
            ) ?: throw TurnStartBlocked()
        return TurnId(turnId)
    }

    override suspend fun cancel(turnId: TurnId): CancelResult {
        val phase = host.persistedPhase(turnId.value)
        if (phase == null) return CancelResult.NotFound
        // The durable terminal can precede release of the live owner or its successor handoff.
        // Only the host can atomically stop that delivery without revoking a newer Turn's Goal.
        return when (val outcome = host.cancelTurn(turnId.value)) {
            is TurnCancelOutcome.AlreadyTerminal -> CancelResult.AlreadyTerminal(outcome.phase)
            TurnCancelOutcome.StoppedLive -> CancelResult.StopAccepted
            TurnCancelOutcome.DiscardedParked -> CancelResult.Cancelled
            TurnCancelOutcome.ReviewRequired -> CancelResult.ReviewRequired
        }
    }

    override fun observe(turnId: TurnId): Flow<TurnSnapshot> =
        flow {
            var sawDurableStop = false
            host
                .observeTurnFrames(turnId.value)
                .map { frame -> liveSnapshot(turnId, frame) }
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
                host.persistedPhase(turnId.value)?.let { phase -> emit(persistedSnapshot(turnId, phase)) }
            }
        }

    /** A live frame for this turn, projected onto a [TurnSnapshot] (streaming text carried). */
    private fun liveSnapshot(
        turnId: TurnId,
        frame: TurnUi,
    ): TurnSnapshot =
        TurnSnapshot(
            turnId = turnId,
            phase = frame.state,
            assistantText = textFor(turnId, frame.state, frame.streamingText),
            errorLabel = frame.errorLabel,
            retryable = frame.retryable,
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
    ): String? = if (phase.isTerminal) host.persistedAssistantText(turnId.value) ?: liveText else liveText

    private companion object {
        val PARKED_PHASES = setOf(TurnState.NEEDS_REVIEW, TurnState.INTERRUPTED)
    }
}
