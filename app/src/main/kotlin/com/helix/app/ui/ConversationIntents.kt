package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.AgentMode

data class ConversationIntents(
    val onBack: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onCompact: () -> Unit = {},
    val onDismissBlocked: () -> Unit,
    val onApproveApproval: (String) -> Unit,
    val onDenyApproval: (String) -> Unit,
    val onStageAttachment: (String) -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    /** HXA-056: bind a tested provider to the open (provider-free) draft session. */
    val onBindProvider: (ProviderRowUi) -> Unit,
    val onSetMode: (AgentMode) -> Unit,
    val onSetChatTools: (Boolean) -> Unit,
    val onSelectModel: (String, String) -> Unit = { _, _ -> },
    val onSetReasoning: (com.helix.core.model.ReasoningEffort) -> Unit = {},
    val onNew: () -> Unit = {},
    val onTasks: () -> Unit = {},
    val onNavigation: () -> Unit = {},
    val onManageGoal: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDirectory: () -> Unit = {},
    val onRecoverSubscriptionResult: (String, String) -> Unit = { _, _ -> },
    val onInspectSubscription: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onRecoverProot: (String, String) -> Unit = { _, _ -> },
    val onRetryProotAck: (String, String) -> Unit = { _, _ -> },
    val onInspectProot: (String, String, Boolean) -> Unit = { _, _, _ -> },
    /** HXA-204 slice 2: the turn recovery panel operations (turn-scoped, own admissions). */
    val onRecoveryReconnect: (String) -> Unit = {},
    val onRecoveryQueryResult: (String) -> Unit = {},
    val onRecoveryGrantPermission: (String) -> Unit = {},
    val onRecoveryContinueGoal: (String) -> Unit = {},
    val onRecoveryRetry: (String) -> Unit = {},
    /** HXA-194: open a command call's read-only details page from its tool row. */
    val onOpenCommandDetail: (String, String) -> Unit = { _, _ -> },
)
