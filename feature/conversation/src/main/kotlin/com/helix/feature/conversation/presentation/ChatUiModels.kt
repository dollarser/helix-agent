package com.helix.feature.conversation.presentation

import com.helix.core.model.TurnState
import com.helix.feature.conversation.application.EgressDisclosure
import com.helix.feature.conversation.presentation.ChatContextUsage
import com.helix.feature.conversation.presentation.ChatImageUi
import com.helix.feature.conversation.presentation.LedgerItemUi
import com.helix.feature.conversation.presentation.SelectedModelUi
import com.helix.feature.conversation.presentation.SessionModelSelectionSnapshot
import com.helix.feature.conversation.presentation.SessionRowUi
import com.helix.feature.conversation.presentation.SubscriptionRecoveryUi
import com.helix.feature.conversation.presentation.TurnRecoveryPanelUi

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
    val knowledgeReferences: List<com.helix.core.model.input.KnowledgeReference> = emptyList(),
    val interruptionNotice: com.helix.core.agent.TurnInterruptionNotice? = null,
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
    val modelStep: Int = 0,
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
    val selectedModel: SelectedModelUi?,
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
    val modelBindingIssue: ProviderBindingIssue? = null,
    /** Raw binding and reasoning used for optimistic edits, independent of display availability. */
    val modelSelectionSnapshot: SessionModelSelectionSnapshot? = null,
    /** In-flight explicit recovery operations, keyed "turnId:OPERATION" (HXA-204 slice 2). */
    val recoveryBusy: Set<String> = emptySet(),
    /** The open session's staged attachments (in-memory, local until an explicit send — ADR-0014 §5). */
    val pendingAttachments: List<PendingAttachmentUi> = emptyList(),
    /**
     * A shared-into-Helix TEXT draft to pre-fill the composer once (HXA-056, `ACTION_SEND`):
     * set by the share-input command, consumed one-shot by the UI
     * after the text lands in the input box. Draft text is never sent automatically.
     */
    val shareDraftText: String? = null,
    val subscriptionRecoveries: List<SubscriptionRecoveryUi> = emptyList(),
    val turns: List<TurnUi> = emptyList(),
    val isDraft: Boolean = false,
    val preparingDraft: Boolean = false,
    val sessionTitle: String = "",
    val directoryRef: String? = null,
    val projectId: String? = null,
    val isNewConversation: Boolean = false,
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
    val historyReadFailed: Boolean = false,
    val historyThrough: Long = Long.MAX_VALUE,
    val hasNewerMessages: Boolean = false,
    val focusedHistoryMessage: String? = null,
    val historyFocusRevision: Long = 0,
    val blockedReasonEpoch: Long = 0,
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
