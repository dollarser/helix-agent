package com.helix.app.chat

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.AttachmentBindingIntent
import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.Flow

/**
 * The turn-control surface the app-layer [com.helix.core.agent.AgentRuntime] adapter drives
 * (research doc section 34; HX2-01).
 *
 * [ChatService] implements this and the adapter ([AppAgentRuntime]) depends ONLY on the seam, so
 * the translation between the framework-free core contract and the production turn path is
 * unit-testable without constructing the whole service. This is the convergence point the research
 * doc calls for: an entry point calls the [com.helix.core.agent.AgentRuntime], which calls this
 * seam — never the model provider or the tool pipeline directly.
 */
internal interface AgentTurnHost {
    /**
     * Start a turn for [sessionId] under the explicit per-turn [control]. [attachments] are the
     * producer's approved binding intents (ADR-0014 §5) the host binds to the turn's user message.
     * [clientRequestId] is the producer's stable id for this submission (HX2-01 §2e): the host is
     * idempotent by it — a re-driven start carrying an id it already started returns the existing
     * turn's id, never a second turn. Returns the started turn's id, or null when the session
     * refused the start (fail-closed — no turn row was written).
     */
    @Suppress("LongParameterList") // one start fact per parameter (see the KDoc)
    suspend fun startTurn(
        sessionId: String,
        clientRequestId: String,
        text: String?,
        providerId: String,
        retryTurnId: String?,
        goalId: String?,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        control: RunControlConfig,
        continuousGoal: Boolean = false,
        goalContinuation: com.helix.core.agent.GoalContinuationRequest? = null,
        directUserRequest: Boolean = false,
        revisedMessageId: String? = null,
        regenerateMessageId: String? = null,
    ): String?

    /**
     * Cancel the turn by id, reporting what actually happened so the adapter's
     * [com.helix.core.agent.CancelResult] is honest. A turn with a live loop has that loop stopped
     * ([TurnCancelOutcome.StoppedLive] — it unwinds to CANCELLED and settles asynchronously); a
     * non-terminal turn with NO live loop is directly discardable only when it is recovered
     * INTERRUPTED work. NEEDS_REVIEW must be resolved through the explicit review flow. The adapter
     * maps StoppedLive to [com.helix.core.agent.CancelResult.StopAccepted], DiscardedParked to
     * [com.helix.core.agent.CancelResult.Cancelled], and ReviewRequired to
     * [com.helix.core.agent.CancelResult.ReviewRequired]. Called for every existing turn: a durable
     * terminal may still own pending delivery, which the host checks atomically before stopping it.
     */
    suspend fun cancelTurn(turnId: String): TurnCancelOutcome

    /**
     * The turn's LIVE frame stream (research doc section 34; HX2-01 §2c): [TurnUi] frames whose
     * lifetime equals the turn's, independent of the open session's UI screen. This is what lets
     * [com.helix.core.agent.AgentRuntime.observe] stream a turn in a NON-open (background) session
     * until it terminalizes or durably parks — the open-session screen only reflects the one
     * session the user is looking at. A turn that is not live (not yet started, already ended, or
     * parked) yields an empty flow, and the adapter falls back to the turn's persisted state.
     */
    fun observeTurnFrames(turnId: String): Flow<TurnUi>

    /** The turn's persisted phase, or null when the id addresses no turn row. */
    fun persistedPhase(turnId: String): TurnState?

    /** The turn's persisted terminal assistant text, or null. */
    fun persistedAssistantText(turnId: String): String?
}

/** The turn could not start (its session refused it) — the caller surfaces a safe reason. */
internal class TurnStartBlocked(
    message: String = "the turn could not start; its session refused the start",
) : RuntimeException(message)

/**
 * What a turn cancel actually did (research doc section 34; HX2-01) — the input to an honest
 * [com.helix.core.agent.CancelResult]. A stopped live loop settles asynchronously as it unwinds
 * (StopAccepted); a discarded INTERRUPTED turn is already settled in CANCELLED; NEEDS_REVIEW is
 * intentionally not cancelled and returns ReviewRequired so uncertain external effects cannot be
 * silently discarded.
 *
 * Public (not internal) because [AgentTurnHost] is implemented by the public [ChatService]; its
 * [cancelTurn] override is a public member and may not expose an internal return type.
 */
sealed interface TurnCancelOutcome {
    /** The loop completed while cancellation was being admitted. */
    data class AlreadyTerminal(
        val phase: TurnState,
    ) : TurnCancelOutcome

    /** A live loop existed and was cancelled; it unwinds to CANCELLED. */
    data object StoppedLive : TurnCancelOutcome

    /** No live loop; a parked (INTERRUPTED) non-terminal turn was written to CANCELLED. */
    data object DiscardedParked : TurnCancelOutcome

    /** NEEDS_REVIEW cannot be discarded by ordinary cancel; the user must resolve the effect review. */
    data object ReviewRequired : TurnCancelOutcome
}
