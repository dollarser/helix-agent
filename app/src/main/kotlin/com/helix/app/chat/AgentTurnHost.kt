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
     * Returns the new turn's id, or null when the session refused the start (fail-closed — no turn
     * row was written).
     */
    suspend fun startTurn(
        sessionId: String,
        text: String?,
        providerId: String,
        retryTurnId: String?,
        goalId: String?,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        control: RunControlConfig,
    ): String?

    /** Cancel the turn's live work by id (a no-op when it is no longer its session's active turn). */
    fun cancelTurn(turnId: String)

    /** The open session's active turn, or null — the live-frame source for [AgentRuntime.observe]. */
    val activeTurn: Flow<TurnUi?>

    /** The turn's persisted phase, or null when the id addresses no turn row. */
    fun persistedPhase(turnId: String): TurnState?

    /** The turn's persisted terminal assistant text, or null. */
    fun persistedAssistantText(turnId: String): String?
}

/** The turn could not start (its session refused it) — the caller surfaces a safe reason. */
internal class TurnStartBlocked(
    message: String = "the turn could not start; its session refused the start",
) : RuntimeException(message)
