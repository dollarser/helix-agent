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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionErrorMapper
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.ChatSubmissionReceipt
import com.helix.app.provider.ProviderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

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
// Explicit application-service dependencies at the screen boundary.
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun ChatScreen(
    chatService: ChatService,
    providerService: ProviderService,
    privacyDeletionService: com.helix.app.privacy.PrivacyDeletionService,
    fileManager: com.helix.app.files.FileManagerService? = null,
    onNavigation: () -> Unit = {},
    onProviders: () -> Unit = {},
    onOpenCommandDetail: (String, String) -> Unit = { _, _ -> },
    sessionExport: com.helix.app.export.SessionExportService? = null,
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val sessions by chatService.sessions.collectAsStateWithLifecycle()
    val sessionSearch by chatService.sessionSearch.collectAsStateWithLifecycle()
    val profile by chatService.profile.collectAsStateWithLifecycle()
    val runControl by chatService.runControl.collectAsStateWithLifecycle()
    val providerRows by providerService.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var renameId by remember { mutableStateOf<String?>(null) }
    var directoryOpen by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var composerRevision by rememberSaveable { mutableStateOf(0L) }
    var clientRequestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var lastSavedText by rememberSaveable { mutableStateOf("") }
    var lastSavedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var isSendingSubmission by remember { mutableStateOf(false) }
    var activeSubmission by remember { mutableStateOf<ChatSubmission?>(null) }
    var currentSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val reminderGoal by chatService.reminderGoal.collectAsStateWithLifecycle()
    var goalsOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    var exportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    exportSessionId?.let { id ->
        if (sessionExport != null) SessionExportDialog(id, sessionExport) { exportSessionId = null }
    }
    // HXA-204 slice 2: the recovery panel's RECONNECT / GRANT_PERMISSION buttons repair in the
    // providers screen — keep the service's navigation target current on every recomposition.
    chatService.recoverySettingsNavigation = onProviders
    if (tasksOpen) BackgroundTaskDialog(chatService, onDismiss = { tasksOpen = false })
    LaunchedEffect(screen.openSessionId, reminderGoal) { goalsOpen = reminderGoal != null }

    val handleReceipt: (ChatSubmissionReceipt, ChatSubmission) -> Unit = { receipt, submission ->
        when (val outcome = receipt.outcome) {
            is ChatSubmissionOutcome.Accepted -> {
                if (screen.openSessionId == submission.sessionId && composerRevision == submission.revision) {
                    input = ""
                    composerRevision = 0L
                    clientRequestId = UUID.randomUUID().toString()
                    lastSavedText = ""
                    lastSavedRevision = null
                    activeSubmission = null
                }
                scope.launch {
                    chatService.acknowledgeSubmission(receipt)
                }
            }

            is ChatSubmissionOutcome.PendingConfirmation -> {
                // Input is retained; screen.pendingDisclosure will render DisclosureDialog.
            }

            is ChatSubmissionOutcome.Rejected -> {
                val mapped = ChatSubmissionErrorMapper.mapReason(outcome.reason, context)
                if (mapped != null) {
                    chatService.showBlockedReason(mapped)
                }
            }
        }
    }

    val saveCurrentDraftNow: suspend () -> Unit = {
        val sessionId = screen.openSessionId
        if (sessionId != null && input != lastSavedText && !isSendingSubmission) {
            if (screen.isDraft) {
                chatService.materializeDraftSession()
            }
            val expected = lastSavedRevision
            val nextRev = expected?.plus(1L) ?: 0L
            val nextReqId = UUID.randomUUID().toString()
            val stagedIds = chatService.currentStagedAttachmentIds()
            val submission = ChatSubmission(sessionId, nextRev, nextReqId, input, stagedIds)
            val saved = chatService.saveComposerDraft(submission, expected)
            if (saved) {
                lastSavedText = input
                lastSavedRevision = nextRev
                composerRevision = nextRev
                clientRequestId = nextReqId
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        scope.launch { saveCurrentDraftNow() }
    }

    LaunchedEffect(input, screen.openSessionId) {
        val sessionId = screen.openSessionId ?: return@LaunchedEffect
        if (input == lastSavedText || isSendingSubmission) return@LaunchedEffect
        delay(500)
        saveCurrentDraftNow()
    }

    LaunchedEffect(screen.openSessionId) {
        val newSessionId = screen.openSessionId
        val oldSessionId = currentSessionId
        if (oldSessionId != null && oldSessionId != newSessionId && input != lastSavedText) {
            val expected = lastSavedRevision
            val nextRev = expected?.plus(1L) ?: 0L
            val nextReqId = UUID.randomUUID().toString()
            val stagedIds = chatService.currentStagedAttachmentIds()
            chatService.saveComposerDraft(
                ChatSubmission(oldSessionId, nextRev, nextReqId, input, stagedIds),
                expected,
            )
        }
        currentSessionId = newSessionId
        if (newSessionId == null) {
            input = ""
            composerRevision = 0L
            clientRequestId = UUID.randomUUID().toString()
            lastSavedText = ""
            lastSavedRevision = null
            activeSubmission = null
            return@LaunchedEffect
        }
        val draft = chatService.loadComposerDraft(newSessionId)
        if (draft != null) {
            input = draft.text
            composerRevision = draft.revision
            clientRequestId = draft.clientRequestId
            lastSavedText = draft.text
            lastSavedRevision = draft.revision
            if (draft.attachmentIds.isNotEmpty()) {
                val missing = chatService.restoreDraftAttachments(draft.attachmentIds)
                if (missing.isNotEmpty()) {
                    chatService.showBlockedReason(context.getString(R.string.chat_draft_attachment_missing))
                }
            }
        } else {
            input = ""
            composerRevision = 0L
            clientRequestId = UUID.randomUUID().toString()
            lastSavedText = ""
            lastSavedRevision = null
        }
    }

    // HXA-056: a shared-in text draft pre-fills the composer ONCE (one-shot consume — a later
    // session switch or re-share re-arms it, never a stale text lands in a new conversation).
    LaunchedEffect(screen.openSessionId, screen.shareDraftText) {
        val draft = screen.shareDraftText
        if (draft != null) {
            input = draft
            val nextRev = lastSavedRevision?.plus(1L) ?: 0L
            composerRevision = nextRev
            clientRequestId = UUID.randomUUID().toString()
            chatService.consumeShareDraftText()
        }
    }

    val onSendAction: () -> Unit = {
        val sessionId = screen.openSessionId
        if (sessionId != null && !isSendingSubmission) {
            scope.launch {
                isSendingSubmission = true
                try {
                    if (screen.isDraft) {
                        chatService.materializeDraftSession()
                    }
                    val stagedIds = chatService.currentStagedAttachmentIds()
                    val trimmedInput = input.trim()
                    val submission =
                        if (trimmedInput != lastSavedText || lastSavedRevision == null) {
                            val expected = lastSavedRevision
                            val nextRev = expected?.plus(1L) ?: 0L
                            val nextReqId = UUID.randomUUID().toString()
                            val sub = ChatSubmission(sessionId, nextRev, nextReqId, trimmedInput, stagedIds)
                            chatService.saveComposerDraft(sub, expected)
                            lastSavedText = trimmedInput
                            lastSavedRevision = nextRev
                            composerRevision = nextRev
                            clientRequestId = nextReqId
                            sub
                        } else {
                            ChatSubmission(sessionId, composerRevision, clientRequestId, trimmedInput, stagedIds)
                        }
                    activeSubmission = submission
                    val receipt = chatService.sendSubmission(submission).await()
                    handleReceipt(receipt, submission)
                } finally {
                    isSendingSubmission = false
                }
            }
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
                search = sessionSearch,
                onNavigation = onNavigation,
                onNew = { chatService.newSessionDraft() },
                onProviders = onProviders,
                needsProvider = providerRows.none { it.chatSelectable },
                onRename = { renameId = it },
                onOpen = { chatService.openSession(it) },
                onArchive = { chatService.archiveSession(it) },
                onRestore = chatService::restoreSession,
                onTasks = { tasksOpen = true },
                onSearch = { chatService.searchSessions(it) },
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
                        onExport = sessionExport?.let { { exportSessionId = screen.openSessionId } },
                        onDirectory = { directoryOpen = true },
                        onSend = onSendAction,
                        onStop = { chatService.stop() },
                        onStopTurn = { turnId -> chatService.stop(turnId) },
                        onCompact = chatService::compactContext,
                        onFork = chatService::forkFromMessage,
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
                        onRecoveryReconnect = chatService::recoveryReconnect,
                        onRecoveryQueryResult = chatService::recoveryQueryResult,
                        onRecoveryGrantPermission = chatService::recoveryGrantPermission,
                        onRecoveryContinueGoal = chatService::recoveryContinueGoal,
                        onRecoveryRetry = chatService::recoveryRetryNewCall,
                        onOpenCommandDetail = onOpenCommandDetail,
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
            onContinued = {
                if (input.isNotBlank()) {
                    input = ""
                    composerRevision = 0L
                    clientRequestId = UUID.randomUUID().toString()
                    lastSavedText = ""
                    lastSavedRevision = null
                }
            },
            onSettings = onProviders,
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
            onConfirm = {
                val sub = activeSubmission
                if (sub != null) {
                    scope.launch {
                        val receipt = chatService.confirmSubmission(sub).await()
                        handleReceipt(receipt, sub)
                    }
                } else {
                    chatService.confirmSend()
                }
            },
            onDismiss = {
                val sub = activeSubmission
                if (sub != null) {
                    scope.launch {
                        val receipt = chatService.cancelSubmission(sub).await()
                        handleReceipt(receipt, sub)
                    }
                } else {
                    chatService.cancelPendingSend()
                }
            },
        )
    }
}
