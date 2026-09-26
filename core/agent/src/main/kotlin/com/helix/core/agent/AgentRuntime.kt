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
 * never the ModelProvider or the tool pipeline directly. The app-layer implementation routes
 * durable lifecycle decisions through the TurnEngine boundary while the live loop remains an
 * implementation detail. This file owns the framework-free contract so the core stays Android-free;
 * the mode strategy (Chat / Plan / Act / Goal) is selected on one loop via [SubmitTurnCommand.mode].
 *
 * [observe] is a replayable stream: a subscriber that joins late (config change, process
 * recovery) still receives the turn's current durable phase. A live observation ends when the
 * turn terminalizes or parks for explicit review/recovery; a later subscriber re-reads Room.
 * Subscribers hold no coroutine handle — the runtime owns the loop (the UI observes service
 * state; it never holds a Job).
 *
 * This interface deliberately has no generic per-turn resume command. After process death the
 * startup recovery sweep parks unresolved work instead of blindly replaying external effects.
 * Deterministic effect review may resume the SAME durable Turn through the app-layer review/engine
 * command using its persisted runtime snapshot and checkpoints; acknowledged uncertainty abandons
 * that Turn. Other interrupted/retry flows remain explicit. [observe] always reflects the durable
 * parked/terminal phase, while blind automatic replay remains forbidden.
 */
interface AgentRuntime {
    /**
     * Start a new turn for [command] and return its [TurnId]. The returned id is stable for the
     * turn's whole lifetime and addresses [observe] and [cancel]. Idempotent by
     * [SubmitTurnCommand.clientRequestId]: a second [submit] carrying the same id returns the turn
     * the first one already started — it never starts a second turn for the same submission.
     */
    suspend fun submit(command: SubmitTurnCommand): TurnId

    /**
     * Cancel a live or recoverable turn. A live turn (its loop still running) receives the stop
     * and unwinds to [TurnPhase.CANCELLED] through [TurnPhase.CANCELLING] — unless an in-flight
     * external effect becomes uncertain, in which case review parking wins the race. A recovered
     * [TurnPhase.INTERRUPTED] turn may be explicitly discarded. A live [TurnPhase.NEEDS_REVIEW]
     * fact is not discarded by ordinary cancel; its resolution belongs to the review command.
     * Cancelling an already-terminal turn is an idempotent no-op ([CancelResult.AlreadyTerminal]).
     */
    suspend fun cancel(turnId: TurnId): CancelResult

    /** A replayable stream from the current phase until terminal or a durable parked phase. */
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
 *
 * [clientRequestId] is the producer's STABLE id for this submission: the runtime is idempotent by
 * it. A submission is a single logical intent; a producer that may re-drive the same intent (a
 * confirmed egress re-delivered by a double-confirm, a client that retries after a timeout) passes
 * the SAME [clientRequestId] each time and the runtime returns the already-started turn instead of
 * starting a second. A producer whose intents never re-drive (a fresh tap) passes a fresh id per
 * intent. The id is opaque to the runtime — it never inspects its value, only its equality.
 */
data class ConversationReferenceIntent(
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val selectionKind: String,
    val sourceMessageIds: List<String>,
    val content: String,
    val contentSha256: String,
) {
    init {
        require(sourceSessionId.isNotBlank())
        require(selectionKind.isNotBlank())
        require(content.isNotBlank())
        require(contentSha256.length == 64)
    }
}

/**
 * The unified turn-start intent (HX2-01).
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
    val references: List<ConversationReferenceIntent> = emptyList(),
    val clientRequestId: String,
    val continuousGoal: Boolean = false,
    val goalContinuation: GoalContinuationRequest? = null,
    val goalBudgets: com.helix.core.model.GoalBudgets? = null,
    val directUserRequest: Boolean = false,
    val revisedMessageId: String? = null,
    val regenerateMessageId: String? = null,
) {
    init {
        require(goalContinuation == null || !directUserRequest) { "automatic continuation is not a human request" }
        require(goalContinuation == null || (continuousGoal && goalId != null && mode == AgentMode.GOAL)) {
            "continuation requires an activated bound Goal"
        }
        require(text != null || goalId != null || retryTurnId != null) {
            "a turn needs a driver: user text, a bound goal, or a retryTurnId (none provided)"
        }
        require(clientRequestId.isNotBlank()) { "clientRequestId must not be blank" }
        require(revisedMessageId == null || regenerateMessageId == null) {
            "cannot both revise and regenerate"
        }
        require(regenerateMessageId == null || text == null) {
            "regenerate cannot take new user text"
        }
    }
}

/** Live driver claim, not user authority. The host validates it against its current activation. */
data class GoalContinuationRequest(
    val activationId: String,
    val previousTurnId: String,
)

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
 * One observable frame of a turn (HX2-01): the UI-facing projection of the durable Turn phase.
 * [assistantText] is the streaming model text (the persisted text at the terminal); [errorLabel]
 * is a SAFE user-visible label, never a raw exception message;
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
    /**
     * The turn's live loop received the stop; the turn unwinds to [TurnPhase.CANCELLED] and its
     * settlement completes asynchronously — [AgentRuntime.observe] delivers its terminal frame.
     */
    data object StopAccepted : CancelResult

    /** The turn was cancelled and is already settled in [TurnPhase.CANCELLED]. */
    data object Cancelled : CancelResult

    /** The turn has uncertain external effects and must be resolved through the review flow. */
    data object ReviewRequired : CancelResult

    /** The turn was already terminal; cancellation was a no-op. */
    data class AlreadyTerminal(
        val phase: TurnPhase,
    ) : CancelResult

    /** No turn exists for this id. */
    data object NotFound : CancelResult
}
