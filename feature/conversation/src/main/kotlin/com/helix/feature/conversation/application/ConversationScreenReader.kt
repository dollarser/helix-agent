package com.helix.feature.conversation.application

import com.helix.feature.conversation.application.recovery.SubscriptionRecoveryFact
import com.helix.feature.conversation.presentation.ChatContextUsage
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.LedgerItemUi
import com.helix.feature.conversation.presentation.ProviderBindingIssue
import com.helix.feature.conversation.presentation.SelectedModelUi
import com.helix.feature.conversation.presentation.SessionModelSelectionSnapshot
import com.helix.feature.conversation.presentation.TurnRecoverySource
import com.helix.feature.conversation.presentation.TurnUi

data class ConversationScreenRequest(
    val history: ConversationHistoryRequest,
    val sourceActiveTurn: TurnUi?,
) {
    fun matches(
        current: ChatScreenState,
        revision: Long,
    ): Boolean = history.matches(current, revision) && sourceActiveTurn?.id == current.activeTurn?.id

    companion object {
        fun capture(
            sessionId: String?,
            current: ChatScreenState,
            revision: Long,
        ) = ConversationScreenRequest(
            ConversationHistoryRequest.capture(sessionId, current, revision),
            current.activeTurn,
        )
    }
}

/** Session display facts only. Selection is raw admission identity, not a Room configuration row. */
data class ConversationSessionSnapshot(
    val id: String,
    val title: String,
    val directoryRef: String?,
    val projectId: String?,
    val isNewConversation: Boolean,
    val workspaceRecovered: Boolean,
    val selectedModel: SelectedModelUi?,
    val modelBindingIssue: ProviderBindingIssue?,
    val modelSelection: SessionModelSelectionSnapshot,
    val contextUsage: ChatContextUsage,
    val taskLedger: List<LedgerItemUi>,
    val hasGoals: Boolean,
    val isFork: Boolean,
)

/** A missing row is an explicit successful read. Read failures must throw and preserve the old screen. */
data class ConversationScreenSnapshot(
    val request: ConversationScreenRequest,
    val history: ConversationHistorySnapshot,
    val session: ConversationSessionSnapshot?,
    val activeTurn: ConversationTurnSnapshot? = null,
    val turns: List<ConversationTurnSnapshot> = emptyList(),
    val retryTargetTurnId: String? = null,
    val subscriptionRecoveries: List<SubscriptionRecoveryFact> = emptyList(),
    val recoverySources: List<TurnRecoverySource> = emptyList(),
) {
    val sessionMissing: Boolean get() = request.history.sessionId != null && session == null
}

interface ConversationScreenReader {
    /** Existence, metadata, history, recovery and turn facts share one repository snapshot. */
    fun read(request: ConversationScreenRequest): ConversationScreenSnapshot
}
