package com.helix.app.chat

import com.helix.app.agent.ChatContextUsage
import com.helix.app.provider.ProviderBadgeUi
import com.helix.app.todo.LedgerItemUi
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
    val directoryRef: String? = null,
    val providerId: String? = null,
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
                directoryRef = entity.directoryRef,
                providerId = entity.providerId,
            )
    }
}

/** One session hit of the service's bounded session search (HXA-191 slice). */
data class SessionSearchHitUi(
    val sessionId: String,
    val title: String,
    val isArchived: Boolean,
    val matchesTitle: Boolean,
    val matchesMessage: Boolean,
    val messageSnippet: String?,
)

/**
 * The observable outcome of the service-owned session search (HXA-191 slice). [query] is
 * the exact typed query being shown ("" = not searching); [truncated] means more matching
 * sessions exist. Search covers all effective visible text, including older sessions.
 */
data class SessionSearchUiState(
    val query: String = "",
    val hits: List<SessionSearchHitUi> = emptyList(),
    val truncated: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotEmpty()
}

/** One persisted message (role "user"/"assistant"; content already resolved). */
data class MessageUi(
    val id: String,
    val role: String,
    val content: String,
    val turnId: String? = null,
    val sequence: Long? = null,
    val presentation: MessagePresentation = MessagePresentation.NORMAL,
    val images: List<ChatImageUi> = emptyList(),
    val historyContext: MessageHistoryContext = MessageHistoryContext.CURRENT,
)

enum class MessageHistoryContext { CURRENT, BEFORE_EDIT, UNAVAILABLE }

enum class MessagePresentation { NORMAL, PROGRESS, COMPLETED_PROGRESS }

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
    val continueFromResults: Boolean = false,
    val budgetDetail: String? = null,
)

/**
 * A staged (not-yet-sent) chat attachment's display facts only (ADR-0014 §5, HXA-049).
 * [id] is the sanitized artifact id that addresses the staged entry for removal — it is
 * never a filesystem path, and no real path ever appears in this type. Attachments stay
 * local to the open session until an explicit send; staging never reaches the model.
 */
data class PendingAttachmentUi(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val isText: Boolean,
    val isImage: Boolean = false,
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
    /** The latest FAILED turn eligible for retry; a bound Goal must permit explicit continuation. */
    val retryTargetTurnId: String?,
    /**
     * The open session's recovery panels keyed by turn id (HXA-204 slice 2): a read-only
     * projection of persisted facts per settled turn; at most one panel carries the retry
     * admission.
     */
    val recoveryPanels: Map<String, TurnRecoveryPanelUi> = emptyMap(),
    val modelBindingIssue: com.helix.app.provider.ProviderBindingIssue? = null,
    /** In-flight explicit recovery operations, keyed "turnId:OPERATION" (HXA-204 slice 2). */
    val recoveryBusy: Set<String> = emptySet(),
    /** The open session's staged attachments (in-memory, local until an explicit send — ADR-0014 §5). */
    val pendingAttachments: List<PendingAttachmentUi> = emptyList(),
    /**
     * A shared-into-Helix TEXT draft to pre-fill the composer once (HXA-056, `ACTION_SEND`):
     * set by [com.helix.app.chat.ChatService.acceptShareDraft], consumed one-shot by the UI
     * after the text lands in the input box. Draft text is never sent automatically.
     */
    val shareDraftText: String? = null,
    val subscriptionRecoveries: List<SubscriptionRecoveryUi> = emptyList(),
    val turns: List<TurnUi> = emptyList(),
    val isDraft: Boolean = false,
    val preparingDraft: Boolean = false,
    val sessionTitle: String = "",
    val directoryRef: String? = null,
    val workspaceRecovered: Boolean = false,
    val contextUsage: ChatContextUsage = ChatContextUsage(),
    /**
     * The open session's task progress (HX2-07): the model's working-memory ledger projected
     * from its latest successful `todo.write` call; empty when the model has not kept one.
     */
    val taskLedger: List<LedgerItemUi> = emptyList(),
    val hasGoals: Boolean = false,
    val isFork: Boolean = false,
    val historyLimit: Int = 100,
    val hasOlderMessages: Boolean = false,
    val historyThrough: Long = Long.MAX_VALUE,
    val hasNewerMessages: Boolean = false,
    val focusedHistoryMessage: String? = null,
    val historyFocusRevision: Long = 0,
) {
    val isSending: Boolean
        get() =
            preparingDraft ||
                (
                    activeTurn?.let {
                        !it.state.isTerminal && it.state !in setOf(TurnState.NEEDS_REVIEW, TurnState.INTERRUPTED)
                    } ?: false
                )
}
