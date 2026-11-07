package com.helix.feature.conversation.application

import com.helix.core.agent.TurnInterruptionNotice
import com.helix.core.model.input.KnowledgeReference
import com.helix.feature.conversation.presentation.ChatImageUi
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.MessagePresentationFact
import com.helix.feature.conversation.presentation.SelectedModelUi
import com.helix.feature.conversation.presentation.ToolTimelinePosition

/** A display read is bound to its navigation generation, window and live-turn identities. */
data class ConversationHistoryRequest(
    val sessionId: String?,
    val revision: Long,
    val limit: Int,
    val through: Long,
    val sourceSessionId: String?,
    val sourceLimit: Int,
    val sourceThrough: Long,
    val sourceFocusRevision: Long,
    val liveTurnIds: Set<String>,
) {
    fun matches(
        current: ChatScreenState,
        currentRevision: Long,
    ): Boolean =
        revision == currentRevision && sourceSessionId == current.openSessionId &&
            sourceLimit == current.historyLimit && sourceThrough == current.historyThrough &&
            sourceFocusRevision == current.historyFocusRevision &&
            liveTurnIds == current.toolTimeline.map { it.turnId }.toSet()

    companion object {
        const val PAGE_SIZE = 100

        fun capture(
            sessionId: String?,
            current: ChatScreenState,
            revision: Long,
        ): ConversationHistoryRequest {
            val sameSession = sessionId == current.openSessionId
            return ConversationHistoryRequest(
                sessionId,
                revision,
                if (sameSession) current.historyLimit else PAGE_SIZE,
                if (sameSession) current.historyThrough else Long.MAX_VALUE,
                current.openSessionId,
                current.historyLimit,
                current.historyThrough,
                current.historyFocusRevision,
                current.toolTimeline.map { it.turnId }.toSet(),
            )
        }
    }
}

/** Decoded display categories, not the persistence format or model-history protocol. */
enum class ConversationMessageVisibility { CONVERSATION, INPUT_RECOVERY_NOTICE, HIDDEN }

data class ConversationMessageSnapshot(
    val presentation: MessagePresentationFact,
    val visibility: ConversationMessageVisibility,
    val content: String?,
    val images: List<ChatImageUi> = emptyList(),
    val knowledgeReferences: List<KnowledgeReference> = emptyList(),
)

data class ConversationNoticeSnapshot(
    val saved: TurnInterruptionNotice?,
    val state: String,
    val userStopped: Boolean,
    val unresolvedEffects: Boolean,
    val inspection: Boolean,
)

/** Only facts needed to display a Turn; no durable entity or execution handle crosses this port. */
data class ConversationTurnSnapshot(
    val id: String,
    val state: String,
    val errorLabel: String? = null,
    val continueFromResults: Boolean = false,
    val budgetDetail: String? = null,
    val modelStep: Int = 0,
)

data class ConversationTimelineTurn(
    val id: String,
    val state: String,
)

data class ConversationToolSnapshot(
    val turnId: String,
    val callId: String,
    val toolName: String,
    val modelIntent: String?,
    val requestSummary: String,
    val state: String,
    val turnState: String,
    val resultSummary: String?,
    val position: ToolTimelinePosition?,
    val prootRecoveryAvailable: Boolean,
)

/** Every persisted field in this value is read in the same repository snapshot. */
data class ConversationHistorySnapshot(
    val request: ConversationHistoryRequest,
    val sessionId: String?,
    val messages: List<ConversationMessageSnapshot> = emptyList(),
    val hasOlderMessages: Boolean = false,
    val editRanges: List<LongRange>? = emptyList(),
    val turnStates: Map<String, String> = emptyMap(),
    val notices: Map<String, ConversationNoticeSnapshot> = emptyMap(),
    val tools: List<ConversationToolSnapshot> = emptyList(),
    val liveTurns: List<ConversationTimelineTurn> = emptyList(),
)

/** Concrete reads only. Implementations own transactions, content decoding and durable eligibility. */
interface ConversationHistoryReader {
    fun positionForCall(
        sessionId: String,
        callId: String,
    ): ToolTimelinePosition?

    fun read(request: ConversationHistoryRequest): ConversationHistorySnapshot

    fun messagesForTurn(turnId: String): ConversationHistorySnapshot

    fun turn(turnId: String): ConversationTurnSnapshot?

    fun retryTarget(sessionId: String?): String?

    fun selectedModel(sessionId: String): SelectedModelUi?

    fun selectedModel(
        providerId: String?,
        modelId: String?,
    ): SelectedModelUi?
}
