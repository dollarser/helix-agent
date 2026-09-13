package com.helix.app.chat

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.CancelResult
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.agent.TurnSnapshot
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The app-layer [AgentRuntime] (research doc section 34; HX2-01): the production turn path exposed
 * through the framework-free core contract. Every entry point (Chat / Goal / Share / Voice /
 * Widget / Channel) drives a turn through this, and this delegates to the existing, proven turn
 * machinery via [AgentTurnHost] — never reaching into the model provider or the tool pipeline.
 *
 * [observe] is replayable: while a turn is the open session's active turn it streams that turn's
 * live frames (streaming text included); a subscriber that joins after the turn terminalized, or a
 * turn that is not the open session's active turn, is projected from its persisted state. The
 * stream ends once the turn's terminal phase is observed. (Live streaming requires the turn to be
 * the open session's active turn — the normal submit-then-observe flow; a turn in another session
 * is observable as its current persisted state.)
 */
internal class AppAgentRuntime(
    private val host: AgentTurnHost,
) : AgentRuntime {
    override suspend fun submit(command: SubmitTurnCommand): TurnId {
        val turnId =
            host.startTurn(
                sessionId = command.session.value,
                text = command.text,
                providerId = command.providerId.value,
                retryTurnId = command.retryTurnId?.value,
                goalId = command.goalId?.value,
                attachments = command.attachments,
                control =
                    RunControlConfig(command.mode, command.chatToolsEnabled, command.budgets, command.reasoning),
            ) ?: throw TurnStartBlocked()
        return TurnId(turnId)
    }

    override suspend fun cancel(turnId: TurnId): CancelResult {
        val phase = host.persistedPhase(turnId.value) ?: return CancelResult.NotFound
        return if (phase.isTerminal) {
            CancelResult.AlreadyTerminal(phase)
        } else {
            host.cancelTurn(turnId.value)
            CancelResult.Cancelled
        }
    }

    override fun observe(turnId: TurnId): Flow<TurnSnapshot> =
        flow {
            host.activeTurn
                .map { frame -> snapshotFor(turnId, frame) }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { snapshot ->
                    emit(snapshot)
                    if (snapshot.isTerminal) throw TERMINAL_DELIVERED
                }
        }.catch { if (it !== TERMINAL_DELIVERED) throw it }

    /**
     * Project the current live frame (if it is this turn) or the turn's persisted state (if the
     * live frame is absent — a late subscriber, or a turn that is not the open session's active
     * turn) onto a [TurnSnapshot]; null when the turn does not exist.
     */
    private fun snapshotFor(
        turnId: TurnId,
        active: TurnUi?,
    ): TurnSnapshot? =
        if (active != null && active.id == turnId.value) {
            TurnSnapshot(
                turnId = turnId,
                phase = active.state,
                assistantText = textFor(turnId, active.state, active.streamingText),
                errorLabel = active.errorLabel,
                retryable = active.retryable,
            )
        } else {
            val phase = host.persistedPhase(turnId.value) ?: return null
            TurnSnapshot(
                turnId = turnId,
                phase = phase,
                assistantText = textFor(turnId, phase, null),
                errorLabel = null,
                retryable = phase == TurnState.FAILED,
            )
        }

    /** The terminal frame carries the turn's persisted text; a live frame carries its streaming text. */
    private fun textFor(
        turnId: TurnId,
        phase: TurnState,
        liveText: String?,
    ): String? = if (phase.isTerminal) host.persistedAssistantText(turnId.value) ?: liveText else liveText

    companion object {
        /**
         * Delivers the turn's terminal frame and ends the stream: this coroutines version of
         * [kotlinx.coroutines.flow.takeWhile] has no inclusive form, so the terminal frame (which
         * must be emitted) is signalled with a sentinel that the [kotlinx.coroutines.flow.catch]
         * above converts back into a clean completion rather than an error.
         */
        private val TERMINAL_DELIVERED = RuntimeException("turn observe: terminal frame delivered")
    }
}
