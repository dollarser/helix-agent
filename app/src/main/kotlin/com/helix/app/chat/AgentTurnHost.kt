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
    ): String?

    /**
     * Cancel the turn by id, reporting what actually happened so the adapter's
     * [com.helix.core.agent.CancelResult] is honest. A turn with a live loop has that loop stopped
     * ([TurnCancelOutcome.StoppedLive] — it unwinds to CANCELLED); a non-terminal turn with NO
     * live loop (a parked / INTERRUPTED turn) is discarded straight to CANCELLED
     * ([TurnCancelOutcome.DiscardedParked]). Only called for an existing, non-terminal turn — the
     * adapter pre-checks the rest.
     */
    suspend fun cancelTurn(turnId: String): TurnCancelOutcome

    /**
     * The turn's LIVE frame stream (research doc section 34; HX2-01 §2c): [TurnUi] frames whose
     * lifetime equals the turn's, independent of the open session's UI screen. This is what lets
     * [com.helix.core.agent.AgentRuntime.observe] stream a turn in a NON-open (background) session
     * to its terminal — the open-session screen only reflects the one session the user is looking
     * at, so it cannot be the live-frame source for a turn the user is not viewing. A turn that is
     * not live (not yet started, or already ended) yields an empty flow, and the adapter falls
     * back to the turn's persisted state.
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
 * [com.helix.core.agent.CancelResult]. Both cases leave the turn cancelled; the distinction is the
 * mechanism (a live loop was stopped vs. a parked turn was discarded) and keeps the host from
 * silently no-opping a parked turn.
 *
 * Public (not internal) because [AgentTurnHost] is implemented by the public [ChatService]; its
 * [cancelTurn] override is a public member and may not expose an internal return type.
 */
sealed interface TurnCancelOutcome {
    /** A live loop existed and was cancelled; it unwinds to CANCELLED. */
    data object StoppedLive : TurnCancelOutcome

    /** No live loop; a parked (INTERRUPTED) non-terminal turn was written to CANCELLED. */
    data object DiscardedParked : TurnCancelOutcome
}
