package com.helix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmissionErrorMapper
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.ChatSubmissionReceipt
import com.helix.app.provider.ProviderService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val sessionId = screen.openSessionId
    val buffer =
        rememberSaveable(sessionId, saver = ConversationDraftBuffer.Saver) {
            ConversationDraftBuffer(sessionId ?: "closed-composer")
        }
    var editMessageId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editorEpoch by remember(sessionId) { mutableStateOf(0) }
    val reminderGoal by chatService.reminderGoal.collectAsStateWithLifecycle()
    var goalsOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    var exportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    exportSessionId?.let { id ->
        if (sessionExport != null) SessionExportDialog(id, sessionExport) { exportSessionId = null }
    }
    chatService.recoverySettingsNavigation = onProviders
    if (tasksOpen) BackgroundTaskDialog(chatService, onDismiss = { tasksOpen = false })
    LaunchedEffect(sessionId, reminderGoal) { goalsOpen = reminderGoal != null }

    val saveBuffer: suspend () -> Boolean = {
        buffer.persist(chatService::materializeDraftSession, chatService::loadComposerDraft) { request, expected ->
            chatService.saveComposerDraftAsync(request, expected).await()
        }
    }
    val flushBuffer: () -> Unit = {
        // A lifecycle callback requests a flush; it is not a synchronous durability guarantee.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveBuffer() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { flushBuffer() }
    DisposableEffect(buffer) { onDispose { flushBuffer() } }

    LaunchedEffect(buffer, editorEpoch) {
        if (sessionId == null) return@LaunchedEffect
        if (!buffer.initialize(chatService::loadComposerDraft)) return@LaunchedEffect
        buffer.restore {
            val revision = buffer.revisionMessageId
            if (revision != null) {
                val draft = chatService.loadComposerDraft(sessionId)
                if (draft != null && !chatService.acceptedRevision(draft)) {
                    editMessageId = revision
                } else {
                    buffer.initialize(chatService::loadComposerDraft)
                }
            }
            if (buffer.revisionMessageId == null) {
                val receipt = chatService.acceptedComposerReceipt(buffer.value)
                if (receipt != null) {
                    buffer.accepted(receipt, chatService::acknowledgeSubmission, chatService::loadComposerDraft)
                }
                if (buffer.saved != null || buffer.value.attachmentIds.isNotEmpty()) {
                    val missing = chatService.restoreDraftAttachments(sessionId, buffer.value.attachmentIds)
                    buffer.restoredAttachments(missing)
                } else {
                    val live = chatService.screen.value
                    if (live.openSessionId == sessionId && live.isDraft && live.pendingAttachments.isNotEmpty()) {
                        chatService.materializeDraftSession(sessionId)
                    }
                    buffer.restoredAttachments(emptyList())
                    buffer.attachments(chatService.currentStagedAttachmentIds(sessionId))
                }
                val shared =
                    chatService.screen.value
                        .takeIf { it.openSessionId == sessionId }
                        ?.shareDraftText
                if (shared != null) {
                    buffer.edit(shared)
                    chatService.consumeShareDraftText()
                }
            }
        }
    }
    LaunchedEffect(buffer, screen.pendingAttachments, buffer.attachmentsReady, buffer.sending, editMessageId) {
        if (sessionId != null && buffer.attachmentsReady && editMessageId == null) {
            buffer.attachments(chatService.currentStagedAttachmentIds(sessionId))
        }
    }
    LaunchedEffect(buffer, buffer.value, buffer.saved, buffer.ready, buffer.sending, editMessageId) {
        if (sessionId == null || editMessageId != null) return@LaunchedEffect
        if (!buffer.ready || buffer.sending || !buffer.dirty) return@LaunchedEffect
        delay(500)
        withContext(NonCancellable) { saveBuffer() }
    }
    LaunchedEffect(sessionId, screen.shareDraftText, buffer.ready) {
        if (buffer.ready && buffer.revisionMessageId == null) {
            screen.shareDraftText?.let {
                buffer.edit(it)
                chatService.consumeShareDraftText()
            }
        }
    }
    editMessageId?.let { messageId ->
        sessionId?.let { id ->
            MessageRevisionDialog(chatService, id, messageId, screen) {
                editMessageId = null
                editorEpoch += 1
            }
        }
    }

    val handleReceipt: suspend (ChatSubmissionReceipt) -> Unit = { receipt ->
        when (val outcome = receipt.outcome) {
            is ChatSubmissionOutcome.Accepted -> {
                buffer.accepted(receipt, chatService::acknowledgeSubmission, chatService::loadComposerDraft)
            }

            ChatSubmissionOutcome.PendingConfirmation -> {
                Unit
            }

            is ChatSubmissionOutcome.Rejected -> {
                if (chatService.screen.value.openSessionId == receipt.submission.sessionId) {
                    ChatSubmissionErrorMapper.mapReason(outcome.reason, context)?.let(chatService::showBlockedReason)
                }
            }
        }
    }
    val onSendAction: () -> Unit = {
        val available = buffer.editable && buffer.canSubmit && !screen.isSending
        if (sessionId != null && available) {
            // Capture the editor's identity before the first suspension, including attachment selection.
            buffer.attachments(chatService.currentStagedAttachmentIds(sessionId))
            buffer.edit(buffer.value.text.trim())
            val intent = buffer.value
            buffer.sending = true
            scope.launch {
                try {
                    val saved =
                        buffer.persist(chatService::materializeDraftSession, chatService::loadComposerDraft, intent) {
                            request,
                            expected,
                            ->
                            chatService.saveComposerDraftAsync(request, expected).await()
                        }
                    if (saved) {
                        val request = buffer.saved
                        if (request != null && request.clientRequestId == intent.clientRequestId) {
                            handleReceipt(chatService.sendSubmission(request).await())
                        }
                    }
                } finally {
                    buffer.sending = false
                }
            }
        }
    }
    val input = buffer.value.text
    val navigateAfterSave: (() -> Unit) -> Unit = { navigate ->
        scope.launch {
            val canLeave = buffer.revisionMessageId != null || saveBuffer()
            if (canLeave && chatService.screen.value.openSessionId == sessionId) navigate()
        }
    }
    BackHandler(enabled = sessionId != null) { navigateAfterSave(chatService::closeSession) }

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
                onInput = buffer::edit,
                composerAvailability =
                    ComposerAvailability(
                        input = buffer.editable && editMessageId == null,
                        attachments = !buffer.sending && screen.pendingDisclosure == null,
                        delivery = buffer.editable && buffer.canSubmit,
                    ),
                composerStatus = {
                    val showStatus = buffer.dirty || buffer.saved != null || buffer.failed
                    if (buffer.revisionMessageId == null && showStatus) {
                        Text(
                            stringResource(
                                when {
                                    buffer.failed -> R.string.message_revision_save_failed
                                    buffer.dirty -> R.string.message_revision_saving
                                    else -> R.string.message_revision_saved
                                },
                            ),
                            modifier = Modifier.testTag("chat-draft-status"),
                        )
                    }
                    if (buffer.failed) {
                        TextButton(onClick = { if (buffer.editable) flushBuffer() else editorEpoch += 1 }) {
                            Text(stringResource(R.string.chat_retry))
                        }
                    }
                    if (buffer.missingAttachments.isNotEmpty()) {
                        Text(stringResource(R.string.chat_draft_attachment_missing))
                        TextButton(onClick = buffer::discardMissingAttachments) {
                            Text(stringResource(R.string.chat_draft_remove_missing))
                        }
                    }
                },
                bindableProviders = providerRows.filter { it.chatSelectable },
                intents =
                    ConversationIntents(
                        onBack = { navigateAfterSave(chatService::closeSession) },
                        onNavigation = { navigateAfterSave(onNavigation) },
                        onManageGoal = { goalsOpen = true },
                        onTasks = { tasksOpen = true },
                        onNew = { navigateAfterSave { chatService.newSessionDraft() } },
                        onRename = { renameId = screen.openSessionId },
                        onExport = sessionExport?.let { { exportSessionId = screen.openSessionId } },
                        onDirectory = { directoryOpen = true },
                        onSend = onSendAction,
                        onStop = { chatService.stop() },
                        onStopTurn = { turnId -> chatService.stop(turnId) },
                        onCompact = chatService::compactContext,
                        onFork = { messageId -> navigateAfterSave { chatService.forkFromMessage(messageId) } },
                        onEditLatest = { messageId ->
                            scope.launch {
                                if (saveBuffer()) editMessageId = messageId
                            }
                        },
                        onDismissBlocked = { chatService.dismissBlocked() },
                        onApproveApproval = { chatService.approveApproval(it) },
                        onDenyApproval = { chatService.denyApproval(it) },
                        onStageAttachment = { uri ->
                            scope.launch {
                                buffer.restore {
                                    val materialized =
                                        sessionId != null &&
                                            chatService.materializeDraftSession(sessionId) == sessionId
                                    if (materialized &&
                                        chatService.screen.value.openSessionId == sessionId
                                    ) {
                                        chatService.stageAttachment(uri)
                                    }
                                }
                            }
                        },
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
            // Goal continuation has no durable receipt yet. Preserve the composer instead of
            // treating a fire-and-forget action (or a refusal) as successful consumption.
            onContinued = {},
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
                val pending = chatService.pendingComposerSubmission()
                if (pending != null) {
                    scope.launch { handleReceipt(chatService.confirmSubmission(pending).await()) }
                } else {
                    chatService.confirmSend()
                }
            },
            onDismiss = {
                val pending = chatService.pendingComposerSubmission()
                if (pending != null) {
                    scope.launch { handleReceipt(chatService.cancelSubmission(pending).await()) }
                } else {
                    chatService.cancelPendingSend()
                }
            },
        )
    }
}
