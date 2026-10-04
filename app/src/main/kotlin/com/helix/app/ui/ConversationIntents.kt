package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderProvisioningKind

data class ConversationIntents(
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onStopTurn: ((String) -> Unit)? = null,
    val onCompact: () -> Unit = {},
    val onEditLatest: ((String) -> Unit)? = null,
    val onRegenerateLatest: ((String) -> Unit)? = null,
    val onFork: ((String) -> Unit)? = null,
    val onDismissBlocked: () -> Unit,
    val onApproveApproval: (String) -> Unit,
    val onDenyApproval: (String) -> Unit,
    val onStageAttachment: (String) -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onSetMode: (AgentMode) -> Unit,
    val onCommandMode: suspend (AgentMode) -> Boolean = {
        onSetMode(it)
        true
    },
    val onSetChatTools: (Boolean) -> Unit,
    val onSelectPermission: suspend (com.helix.core.model.SessionPermissionMode) -> Boolean = { false },
    val onSelectModel: (String, String) -> Unit = { _, _ -> },
    val onSetReasoning: (com.helix.core.model.ReasoningEffort) -> Unit = {},
    val onNew: () -> Unit = {},
    val onTasks: () -> Unit = {},
    val onNavigation: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onGit: () -> Unit = {},
    val onManageModels: () -> Unit = {},
    val onConfigureModelSource: ((ProviderProvisioningKind) -> Unit)? = null,
    val onReference: () -> Unit = {},
    val onClearReference: () -> Unit = {},
    val onExpert: () -> Unit = {},
    val onSkills: () -> Unit = {},
    val onConnectors: () -> Unit = {},
    val onManageGoal: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onExport: (() -> Unit)? = null,
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
