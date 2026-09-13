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
 *
 * Recovery is NOT a per-turn operation on this interface. After process death the app's startup
 * sweep (the [RecoveryCoordinator] applied over storage) marks an in-flight turn
 * [TurnPhase.INTERRUPTED] and parks its possibly-unknown tool side effects; the user then
 * explicitly re-drives the work with a new [submit] — or continues a bound goal with a [submit]
 * carrying its goalId. An interrupted turn's state is simply observed through [observe]. Nothing
 * is ever auto-resumed: a possibly-unknown side effect makes a blind replay unsafe, so the
 * contract deliberately offers no per-turn "resume."
 */
interface AgentRuntime {
    /**
     * Start a new turn for [command] and return its [TurnId]. The returned id is stable for the
     * turn's whole lifetime and addresses [observe] and [cancel].
     */
    suspend fun submit(command: SubmitTurnCommand): TurnId

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
