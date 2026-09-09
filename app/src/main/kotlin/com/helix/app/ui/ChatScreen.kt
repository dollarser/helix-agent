package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.chat.ChatService
import com.helix.app.provider.ProviderService
import com.helix.core.model.AgentMode
import kotlinx.coroutines.launch

/**
 * The chat UI (HXA-028). Two views over the service's observable state:
 * the session list (persisted sessions) and the open conversation
 * (persisted messages + the in-flight turn's [com.helix.app.chat.TurnUi]).
 *
 * The UI dispatches intents to [ChatService] and observes its StateFlows —
 * it NEVER holds a network Job (doc 02 section 12): the streaming Job lives
 * in the service. Error displays use the service's SAFE labels
 * ([com.helix.app.chat.TurnUi.errorLabel]) — never a raw exception message
 * (doc 02 section 13). The pre-send egress gate (ADR-0005 / doc 10 section
 * 2.6) surfaces as the [DisclosureDialog] when the service holds a pending
 * disclosure.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
fun ChatScreen(
    chatService: ChatService,
    providerService: ProviderService,
    privacyDeletionService: com.helix.app.privacy.PrivacyDeletionService,
    fileManager: com.helix.app.files.FileManagerService? = null,
    onNavigation: () -> Unit = {},
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val sessions by chatService.sessions.collectAsStateWithLifecycle()
    val profile by chatService.profile.collectAsStateWithLifecycle()
    val runControl by chatService.runControl.collectAsStateWithLifecycle()
    val providerRows by providerService.rows.collectAsStateWithLifecycle()
    var renameId by remember { mutableStateOf<String?>(null) }
    var directoryOpen by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var input by remember(screen.openSessionId) { mutableStateOf("") }
    val reminderGoal by chatService.reminderGoal.collectAsStateWithLifecycle()
    var goalsOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    if (tasksOpen) BackgroundTaskDialog(chatService, onDismiss = { tasksOpen = false })
    LaunchedEffect(screen.openSessionId, reminderGoal) { goalsOpen = reminderGoal != null }

    // HXA-056: a shared-in text draft pre-fills the composer ONCE (one-shot consume — a later
    // session switch or re-share re-arms it, never a stale text lands in a new conversation).
    LaunchedEffect(screen.openSessionId, screen.shareDraftText) {
        val draft = screen.shareDraftText
        if (draft != null) {
            input = draft
            chatService.consumeShareDraftText()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .testTag("screen-sessions"),
    ) {
        if (screen.openSessionId == null) {
            SessionListSection(
                sessions = sessions,
                onNavigation = onNavigation,
                onNew = { chatService.newSessionDraft() },
                onRename = { renameId = it },
                onOpen = { chatService.openSession(it) },
                onArchive = { chatService.archiveSession(it) },
                onRestore = chatService::restoreSession,
                onTasks = { tasksOpen = true },
            )
        } else {
            ConversationSection(
                screen = screen,
                profile = profile,
                runControl = runControl,
                input = input,
                onInput = { input = it },
                bindableProviders = providerRows.filter { it.chatSelectable },
                intents =
                    ConversationIntents(
                        onBack = { chatService.closeSession() },
                        onNavigation = onNavigation,
                        onManageGoal = { goalsOpen = true },
                        onTasks = { tasksOpen = true },
                        onNew = { chatService.newSessionDraft() },
                        onRename = { renameId = screen.openSessionId },
                        onDirectory = { directoryOpen = true },
                        onSend = {
                            if (runControl.mode == AgentMode.GOAL) {
                                uiScope.launch {
                                    if (!screen.isDraft || chatService.saveDraftForGoal(input.trim())) goalsOpen = true
                                }
                            } else {
                                chatService.send(input.trim())
                                input = ""
                            }
                        },
                        onStop = { chatService.stop() },
                        onCompact = chatService::compactContext,
                        onRetry = { chatService.retry() },
                        onDismissBlocked = { chatService.dismissBlocked() },
                        onApproveApproval = { chatService.approveApproval(it) },
                        onDenyApproval = { chatService.denyApproval(it) },
                        onStageAttachment = { chatService.stageAttachment(it) },
                        onRemoveAttachment = { chatService.removePendingAttachment(it) },
                        onBindProvider = { row -> chatService.bindProviderToSession(row.id, row.model) },
                        onSelectModel = chatService::selectSessionModel,
                        onSetMode = chatService::setMode,
                        onSetReasoning = chatService::setReasoning,
                        onSetChatTools = chatService::setChatToolsEnabled,
                        onInspectProot = chatService::inspectInterruptedProot,
                        onRecoverProot = chatService::recoverInterruptedProot,
                        onRetryProotAck = chatService::retryProotAcknowledgement,
                        onInspectSubscription = chatService::inspectInterruptedSubscription,
                        onRecoverSubscriptionResult = chatService::recoverInterruptedSubscriptionResult,
                    ),
            )
        }
    }

    if (goalsOpen && screen.openSessionId != null) {
        GoalDialog(
            chatService,
            input.trim(),
            onDismiss = {
                goalsOpen = false
                chatService.dismissGoalReminder()
            },
            onContinued = { input = "" },
            busy = screen.isSending,
            selectedGoalId = reminderGoal,
            onDeleteGoal = { id ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    privacyDeletionService.deleteGoal(id)
                }
            },
        )
    }

    renameId?.let { id ->
        SessionRenameDialog(
            if (screen.openSessionId ==
                id
            ) {
                screen.sessionTitle
            } else {
                sessions.firstOrNull { it.id == id }?.title.orEmpty()
            },
            onDismiss = { renameId = null },
            onSave = {
                chatService.renameSession(id, it)
                renameId = null
            },
        )
    }
    if (directoryOpen && fileManager != null) {
        SessionDirectoryDialog(fileManager, onDismiss = { directoryOpen = false }) {
            chatService.setSessionDirectory(it)
            directoryOpen = false
        }
    }

    screen.pendingDisclosure?.let { summary ->
        DisclosureDialog(
            summary = summary,
            onConfirm = { chatService.confirmSend() },
            onDismiss = { chatService.cancelPendingSend() },
        )
    }
}
