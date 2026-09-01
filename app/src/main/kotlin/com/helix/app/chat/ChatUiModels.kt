package com.helix.app.chat

import com.helix.app.provider.ProviderBadgeUi
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.SessionEntity

/** A session row for the session list (persisted state only). */
data class SessionRowUi(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isArchived: Boolean,
    val providerName: String?,
    val model: String?,
) {
    companion object {
        fun from(
            entity: SessionEntity,
            providerName: String?,
        ): SessionRowUi =
            SessionRowUi(
                id = entity.id,
                title = entity.title,
                createdAt = entity.createdAt,
                isArchived = entity.archivedAt != null,
                providerName = providerName,
                model = entity.modelId,
            )
    }
}

/** One persisted message (role "user"/"assistant"; content already resolved). */
data class MessageUi(
    val id: String,
    val role: String,
    val content: String,
)

/**
 * The active turn of the open session. [streamingText] is the in-progress
 * assistant text (persisted at the terminal; shown live from this state —
 * doc 02 section 12: the UI observes service state, it holds no Job).
 * [errorLabel] is a SAFE user-visible label (never a raw exception message).
 */
data class TurnUi(
    val id: String,
    val state: TurnState,
    val streamingText: String?,
    val errorLabel: String?,
    val retryable: Boolean,
)

/**
 * The full observable chat screen (HXA-028). Everything here is either
 * persisted state (sessions/messages/turns) or a transient service fact
 * (the in-flight turn, a pending disclosure, a blocked reason) — never a
 * network handle.
 */
data class ChatScreenState(
    val sessions: List<SessionRowUi>,
    val openSessionId: String?,
    val badge: ProviderBadgeUi?,
    val messages: List<MessageUi>,
    /** The open session's tool timeline rows (requests + results + live approval cards). */
    val toolTimeline: List<ToolTimelineRow>,
    val activeTurn: TurnUi?,
    val pendingDisclosure: EgressDisclosure.EgressSummary?,
    val blockedReason: String?,
    /** The newest FAILED turn of the open session — the retry button target (persisted state). */
    val retryTargetTurnId: String?,
) {
    val isSending: Boolean
        get() = activeTurn?.let { !it.state.isTerminal } ?: false
}
