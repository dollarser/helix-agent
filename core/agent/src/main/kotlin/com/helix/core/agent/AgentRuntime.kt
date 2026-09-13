package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.GoalId
import com.helix.core.model.ProviderId
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import kotlinx.coroutines.flow.Flow
import com.helix.core.model.TurnState as TurnPhase

/**
 * The single entry point every producer (Chat / Goal / Share / Voice / Widget / Channel) uses to
 * drive an agent turn (research doc section 34; HX2-01).
 *
 * Before Harness 2.0 a turn was started by ChatService reaching straight into the model provider
 * and the tool pipeline, and each entry point owned its own launch / resume / cancel / observe
 * plumbing. This interface is the convergence point: an entry point calls ONLY [AgentRuntime],
 * never the ModelProvider or the tool pipeline directly. The implementation (the adapter over the
 * existing TurnCoordinator / GoalRunCoordinator and the model loop) lives in the app layer; this
 * file owns the framework-free contract so the core stays Android-free, and the mode strategy
 * (Chat / Plan / Act / Goal) is a value selected on one loop via [SubmitTurnCommand.mode].
 *
 * [observe] is a replayable stream: a subscriber that joins late (config change, process
 * recovery) still receives the turn's frames from its current phase to the terminal phase.
 * Subscribers hold no coroutine handle — the runtime owns the loop (the UI observes service
 * state; it never holds a Job).
 */
interface AgentRuntime {
    /**
     * Start a new turn for [command] and return its [TurnId]. The returned id is stable for the
     * turn's whole lifetime (including any [resume]) and addresses [observe], [resume] and
     * [cancel].
     */
    suspend fun submit(command: SubmitTurnCommand): TurnId

    /**
     * Resume an [TurnPhase.INTERRUPTED] turn after process recovery (crash / kill / power loss).
     * The caller is expected to have completed the side-effect review for any possibly-unknown
     * tool calls first; [resume] encodes the state transition, not that policy.
     */
    suspend fun resume(turnId: TurnId): ResumeResult

    /**
     * Cancel a live or recoverable turn. A non-terminal phase moves to [TurnPhase.CANCELLING]
     * and then [TurnPhase.CANCELLED]; an [TurnPhase.INTERRUPTED] turn is discarded straight to
     * [TurnPhase.CANCELLED] because no live loop exists to cancel.
     */
    suspend fun cancel(turnId: TurnId): CancelResult

    /** A replayable stream of [TurnSnapshot] for one turn, from its current phase to terminal. */
    fun observe(turnId: TurnId): Flow<TurnSnapshot>
}

/**
 * The unified turn-start intent (HX2-01): exactly what a turn needs to begin, abstracted from any
 * specific entry point. [mode] selects the strategy on the single loop (Chat / Plan / Act / Goal);
 * [budgets] / [chatToolsEnabled] / [reasoning] are the per-turn facts the production run control
 * already snapshots at launch. A [goalId] binds the turn to a Goal (whose budgets supersede the
 * caller's); a [retryTurnId] re-drives the most recent FAILED turn with no new text.
 *
 * A turn's attachments are the producer's approved binding intents ([attachments]): the send
 * path approves ONE enumerated set (ADR-0014 §5) and binds exactly those artifacts, so the
 * approved set travels with the intent — the session's live staged set is the source of truth
 * only until a send approves it. A producer without an approved set passes none.
 */
data class SubmitTurnCommand(
    val session: SessionId,
    val providerId: ProviderId,
    val mode: AgentMode,
    val text: String?,
    val budgets: TurnBudgets,
    val chatToolsEnabled: Boolean = false,
    val reasoning: ReasoningEffort = ReasoningEffort.OFF,
    val goalId: GoalId? = null,
    val retryTurnId: TurnId? = null,
    val attachments: List<AttachmentBindingIntent> = emptyList(),
) {
    init {
        require(text != null || goalId != null || retryTurnId != null) {
            "a turn needs a driver: user text, a bound goal, or a retryTurnId (none provided)"
        }
    }
}

/**
 * One attachment the turn's user message binds (HX2-01): which artifact (an image's NORMALIZED
 * form) and the SHA-256 the producer verified for it (HXA-055). The adapter maps the intent to
 * the persistence binding; the producer never touches a storage entity.
 */
data class AttachmentBindingIntent(
    val artifactId: String,
    val boundSha256: String,
) {
    init {
        require(artifactId.isNotBlank()) { "artifactId must not be blank" }
        require(boundSha256.isNotBlank()) { "boundSha256 must not be blank" }
    }
}

/**
 * One observable frame of a turn (HX2-01): the UI-facing projection of the turn's reducer state
 * ([com.helix.core.agent.TurnState]). [assistantText] is the streaming model text (the persisted
 * text at the terminal); [errorLabel] is a SAFE user-visible label, never a raw exception message;
 * [retryable] marks whether the user may retry. For Act / Goal turns the terminal frame also
 * carries the completion report (HX2-06) and the model's work memory (HX2-07 [TaskLedger]).
 */
data class TurnSnapshot(
    val turnId: TurnId,
    val phase: TurnPhase,
    val assistantText: String? = null,
    val errorLabel: String? = null,
    val retryable: Boolean = false,
    val completionReport: CompletionReport? = null,
    val taskLedger: TaskLedger? = null,
) {
    val isTerminal: Boolean
        get() = phase.isTerminal
}

/** Outcome of [AgentRuntime.resume]. */
sealed interface ResumeResult {
    /** The turn was picked back up and its loop restarted. */
    data object Resumed : ResumeResult

    /** The turn was already terminal; there is nothing to resume. */
    data class AlreadyTerminal(
        val phase: TurnPhase,
    ) : ResumeResult

    /** No turn exists for this id. */
    data object NotFound : ResumeResult

    /** The turn cannot be resumed (e.g. a possibly-unknown tool side effect failed its review). */
    data class Rejected(
        val reason: String,
    ) : ResumeResult
}

/** Outcome of [AgentRuntime.cancel]. */
sealed interface CancelResult {
    /** The turn was cancelled. */
    data object Cancelled : CancelResult

    /** The turn was already terminal; cancellation was a no-op. */
    data class AlreadyTerminal(
        val phase: TurnPhase,
    ) : CancelResult

    /** No turn exists for this id. */
    data object NotFound : CancelResult
}
