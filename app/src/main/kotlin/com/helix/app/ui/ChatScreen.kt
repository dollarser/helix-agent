package com.helix.app.ui

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
import com.helix.app.approval.SessionPermissionEditService
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmissionErrorMapper
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.ChatSubmissionReceipt
import com.helix.app.provider.ProviderService
import com.helix.core.model.SessionPermissionMode
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
    sessionPermissionEdit: SessionPermissionEditService? = null,
    skills: SkillRepository? = null,
    onNavigation: () -> Unit = {},
    onModels: () -> Unit = {},
    onAgentDefaults: () -> Unit = {},
    onPermissions: () -> Unit = {},
    onSessionSettings: () -> Unit = {},
    onOpenCommandDetail: (String, String) -> Unit = { _, _ -> },
    sessionExport: com.helix.app.export.SessionExportService? = null,
    connectors: com.helix.app.plugin.PluginService? = null,
    onExtensions: () -> Unit = {},
    memory: com.helix.app.memory.MemoryService? = null,
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val runControl by chatService.runControl.collectAsStateWithLifecycle()
    val providerRows by providerService.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var renameId by remember { mutableStateOf<String?>(null) }
    var directoryOpen by remember { mutableStateOf(false) }
    val sessionId = screen.openSessionId
    val imageDisclosures by chatService.toolVisionConsent.pending.collectAsStateWithLifecycle()
    if (screen.pendingDisclosure == null) {
        imageDisclosures.firstOrNull { it.sessionId == sessionId }?.let { disclosure ->
            DisclosureDialog(
                disclosure.summary,
                onConfirm = { chatService.toolVisionConsent.respond(disclosure.id, true) },
                onDismiss = { chatService.toolVisionConsent.respond(disclosure.id, false) },
            )
        }
    }
    var connectorsOpen by remember(sessionId) { mutableStateOf(false) }
    var skillsOpen by remember(sessionId) { mutableStateOf(false) }
    var expertOpen by remember(sessionId) { mutableStateOf(false) }
    var referenceOpen by remember(sessionId) { mutableStateOf(false) }
    var memoryOpen by remember(sessionId) { mutableStateOf(false) }
    if (memoryOpen && memory != null) MemoryDialog(memory, sessionId) { memoryOpen = false }
    var permissionRevision by remember(sessionId) { mutableStateOf(0) }
    var permissionMode by remember(sessionId) { mutableStateOf<SessionPermissionMode?>(null) }
    val referenceUnavailableReason = stringResource(R.string.chat_blocked_reference_unavailable)
    val modelRequiredReason = stringResource(R.string.chat_model_required_before_send)
    if (connectorsOpen && sessionId != null && connectors != null) {
        com.helix.app.connector
            .ConnectorSessionPanel(connectors, sessionId, onExtensions) { connectorsOpen = false }
    }
    if (skillsOpen && sessionId != null && skills != null) {
        SkillSessionPanel(skills, sessionId, onExtensions) { skillsOpen = false }
    }
    val buffer =
        rememberSaveable(sessionId, saver = ConversationDraftBuffer.saverFor(sessionId ?: "closed-composer")) {
            ConversationDraftBuffer(sessionId ?: "closed-composer")
        }
    if (expertOpen && sessionId != null) {
        SessionExpertDialog(chatService, sessionId) { expertOpen = false }
    }
    if (referenceOpen && sessionId != null) {
        ConversationReferencePicker(
            sessions = screen.sessions,
            currentSessionId = sessionId,
            onSelect = { row ->
                scope.launch {
                    val kind = chatService.referenceKindForSession(sessionId, row.id).await()
                    referenceOpen = false
                    if (kind != null) {
                        buffer.reference(row.id, kind)
                    } else {
                        chatService.showBlockedReason(referenceUnavailableReason)
                    }
                }
            },
            onDismiss = { referenceOpen = false },
            onMemory =
                memory?.let {
                    {
                        referenceOpen = false
                        memoryOpen = true
                    }
                },
        )
    }
    var editMessageId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var dismissedRevisionId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editorEpoch by remember(sessionId) { mutableStateOf(0) }
    var inputQueueEpoch by remember(sessionId) { mutableStateOf(0) }
    val reminderGoal by chatService.reminderGoal.collectAsStateWithLifecycle()
    var goalsOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    var exportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    exportSessionId?.let { id ->
        if (sessionExport != null) SessionExportDialog(id, sessionExport) { exportSessionId = null }
    }
    androidx.compose.runtime.DisposableEffect(chatService, onModels, onPermissions) {
        chatService.recoveryModelsNavigation = onModels
        chatService.recoveryPermissionsNavigation = onPermissions
        onDispose {
            if (chatService.recoveryModelsNavigation === onModels) chatService.recoveryModelsNavigation = null
            if (chatService.recoveryPermissionsNavigation ===
                onPermissions
            ) {
                chatService.recoveryPermissionsNavigation = null
            }
        }
    }
    if (tasksOpen) BackgroundTaskDialog(chatService, onDismiss = { tasksOpen = false })
    LaunchedEffect(sessionId, reminderGoal) { goalsOpen = reminderGoal != null }

    val saveBuffer: suspend () -> Boolean = {
        buffer.persist { request -> chatService.saveComposerDraftAsync(request).await() }
    }
    val flushBuffer: () -> Unit = {
        // A lifecycle callback requests a flush; it is not a synchronous durability guarantee.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveBuffer() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { flushBuffer() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionRevision++ }
    LaunchedEffect(sessionId, permissionRevision, screen.isDraft, sessionPermissionEdit) {
        val id = sessionId
        val edit = sessionPermissionEdit
        permissionMode =
            if (id == null || edit == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    if (screen.isDraft) edit.appDefault().mode else (edit.activeConfigFor(id) ?: edit.appDefault()).mode
                }
            }
    }
    DisposableEffect(buffer) { onDispose { flushBuffer() } }
    val acceptOrdinaryReceipt: suspend (ChatSubmissionReceipt) -> Unit = { receipt ->
        if (receipt.submission.revisedMessageId == null) {
            buffer.accepted(receipt, chatService::acknowledgeSubmission, chatService::loadComposerDraft)
            inputQueueEpoch += 1
        }
    }

    LaunchedEffect(buffer, editorEpoch) {
        if (sessionId == null) return@LaunchedEffect
        if (!buffer.initialize(chatService::loadComposerDraft)) return@LaunchedEffect
        val restored =
            buffer.restore {
                val revision = buffer.revisionMessageId
                if (revision != null) {
                    val draft = chatService.loadComposerDraft(sessionId)
                    if (draft != null && !chatService.acceptedRevision(draft)) {
                        if (dismissedRevisionId != revision) editMessageId = revision
                    } else {
                        buffer.initialize(chatService::loadComposerDraft)
                        dismissedRevisionId = null
                    }
                }
                if (buffer.revisionMessageId == null) {
                    dismissedRevisionId = null
                    val receipt = buffer.acceptedReceiptCandidate?.let { chatService.acceptedComposerReceipt(it) }
                    if (receipt != null) {
                        acceptOrdinaryReceipt(receipt)
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
        if (!restored) buffer.attachmentRestoreFailed()
    }
    LaunchedEffect(
        buffer,
        buffer.ready,
        buffer.saved,
        screen.messages,
        screen.activeTurn?.id,
        screen.pendingDisclosure,
        screen.pendingAttachments,
        buffer.attachmentsReady,
        buffer.sending,
        editMessageId,
    ) {
        if (sessionId == null || !buffer.ready || !buffer.attachmentsReady) return@LaunchedEffect
        if (buffer.revisionMessageId == null) {
            val request = buffer.acceptedReceiptCandidate
            val receipt = request?.let { chatService.acceptedComposerReceipt(it) }
            if (receipt != null) {
                acceptOrdinaryReceipt(receipt)
                return@LaunchedEffect
            }
        }
        if (buffer.attachmentsReady && editMessageId == null) {
            buffer.synchronizeAttachments { chatService.currentStagedAttachmentIds(sessionId) }
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
                dismissedRevisionId = messageId
                editMessageId = null
                editorEpoch += 1
            }
        }
    }

    val handleReceipt: suspend (ChatSubmissionReceipt) -> Unit = { receipt ->
        when (val outcome = receipt.outcome) {
            is ChatSubmissionOutcome.Accepted, is ChatSubmissionOutcome.Enqueued -> {
                acceptOrdinaryReceipt(receipt)
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
        val live = chatService.screen.value
        val selected =
            com.helix.app.chat
                .hasSelectedConversationModel(live.badge?.providerId, live.badge?.model)
        val available = buffer.editable && buffer.canSubmit && live.pendingDisclosure == null
        val currentSession = sessionId?.takeIf { it == live.openSessionId }
        if (currentSession != null && available && !selected) {
            chatService.showBlockedReason(modelRequiredReason)
        } else if (currentSession != null && available) {
            // Capture the editor's identity before the first suspension, including attachment selection.
            buffer.attachments(chatService.currentStagedAttachmentIds(currentSession))
            val intent = buffer.captureSubmission()
            buffer.sending = true
            scope.launch {
                try {
                    handleReceipt(chatService.sendSubmission(intent).await())
                } finally {
                    buffer.sending = false
                }
            }
        }
    }
    val input = buffer.value.text
    val referenceLabel =
        buffer.value.referenceSourceSessionId?.let { referenceSessionId ->
            screen.sessions.firstOrNull { it.id == referenceSessionId }?.title ?: referenceSessionId
        }
    val navigateAfterSave: (() -> Unit) -> Unit = { navigate ->
        scope.launch {
            if (buffer.revisionMessageId == null) saveBuffer()
            if (chatService.screen.value.openSessionId == sessionId) navigate()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .testTag("screen-sessions"),
    ) {
        if (screen.openSessionId == null) {
            LaunchedEffect(screen.preparingDraft) {
                // Activity restoration may still be loading the user's selected conversation.
                // Share its entry point instead of overwriting that target with a new draft.
                if (!screen.preparingDraft) chatService.restoreConversationLaunchTarget()
            }
        } else {
            ConversationSection(
                screen = screen,
                runControl = runControl,
                permissionMode = permissionMode,
                referenceLabel = referenceLabel,
                input = input,
                onInput = buffer::edit,
                composerAvailability =
                    ComposerAvailability(
                        input = buffer.editable && editMessageId == null,
                        attachments = !buffer.sending && screen.pendingDisclosure == null,
                        delivery =
                            buffer.editable && buffer.canSubmit && editMessageId == null &&
                                screen.pendingDisclosure == null,
                        deliveryReason =
                            when {
                                !buffer.ready -> {
                                    R.string.composer_restoring
                                }

                                !buffer.attachmentsReady -> {
                                    R.string.composer_restoring_attachments
                                }

                                buffer.missingAttachments.isNotEmpty() -> {
                                    R.string.chat_draft_attachment_missing
                                }

                                buffer.revisionMessageId != null || editMessageId != null -> {
                                    R.string.composer_editing_message
                                }

                                buffer.sending -> {
                                    R.string.composer_submitting
                                }

                                screen.pendingDisclosure != null -> {
                                    R.string.composer_disclosure_pending
                                }

                                else -> {
                                    null
                                }
                            },
                        modelSelected =
                            com.helix.app.chat.hasSelectedConversationModel(
                                screen.badge?.providerId,
                                screen.badge?.model,
                            ),
                        localCommands = buffer.editable && !buffer.sending && editMessageId == null,
                    ),
                composerStatus = {
                    sessionId?.let { id ->
                        SessionInputQueuePanel(
                            chatService,
                            id,
                            listOf(
                                inputQueueEpoch,
                                screen.activeTurn?.id,
                                screen.activeTurn?.state,
                                screen.messages.size,
                            ),
                        )
                    }
                },
                composerOptions = {
                    SessionInputDeliverySelector(
                        delivery = buffer.value.delivery,
                        expectedTurnId = buffer.value.expectedTurnId,
                        activeTurnId = screen.activeTurn?.takeIf { !it.state.isTerminal }?.id,
                        enabled = buffer.editable && !buffer.sending && screen.pendingDisclosure == null,
                        onSelect = buffer::delivery,
                    )
                },
                composerFeedback = {
                    if (providerRows.firstOrNull { it.id == screen.badge?.providerId }?.isCleartext == true) {
                        Text(
                            stringResource(R.string.chat_cleartext_warning),
                            Modifier.testTag("chat-cleartext-warning"),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (buffer.failed) {
                        Text(stringResource(R.string.composer_cache_notice), Modifier.testTag("chat-cache-notice"))
                    }
                    if (buffer.missingAttachments.isNotEmpty()) {
                        Text(stringResource(R.string.chat_draft_attachment_missing))
                        TextButton(onClick = buffer::discardMissingAttachments) {
                            Text(stringResource(R.string.chat_draft_remove_missing))
                        }
                    }
                },
                bindableProviders = providerRows,
                artifacts = {
                    ConversationArtifacts(chatService, fileManager, screen)
                },
                intents =
                    ConversationIntents(
                        onNavigation = { navigateAfterSave(onNavigation) },
                        onSettings = { navigateAfterSave(onSessionSettings) },
                        onManageModels = { navigateAfterSave(onModels) },
                        onReference = {
                            if (sessionId != null && buffer.editable && !buffer.sending) referenceOpen = true
                        },
                        onClearReference = { buffer.reference(null, null) },
                        onExpert = {
                            val id = sessionId
                            if (id != null) {
                                scope.launch {
                                    saveBuffer()
                                    if (chatService.materializeDraftSession(id) == id) expertOpen = true
                                }
                            }
                        },
                        onSkills = {
                            val id = sessionId
                            if (id != null && skills != null) {
                                scope.launch {
                                    saveBuffer()
                                    if (chatService.materializeDraftSession(id) == id) skillsOpen = true
                                }
                            }
                        },
                        onConnectors = {
                            val id = sessionId
                            if (id != null && connectors != null) {
                                scope.launch {
                                    saveBuffer()
                                    if (chatService.materializeDraftSession(id) == id) {
                                        connectorsOpen = true
                                    }
                                }
                            }
                        },
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
                        onRegenerateLatest = { messageId -> chatService.regenerateLatestTurn(messageId) },
                        onEditLatest = { messageId ->
                            if (buffer.revisionMessageId == messageId) {
                                dismissedRevisionId = null
                                editMessageId = messageId
                            } else {
                                scope.launch {
                                    saveBuffer()
                                    dismissedRevisionId = null
                                    editMessageId = messageId
                                }
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
                                        chatService.stageAttachment(uri, sessionId)
                                    }
                                }
                            }
                        },
                        onRemoveAttachment = { chatService.removePendingAttachment(it) },
                        onSelectPermission = { selected ->
                            val id = sessionId
                            val edit = sessionPermissionEdit
                            val matches = id != null && chatService.screen.value.openSessionId == id
                            val materialized =
                                matches &&
                                    chatService.materializeDraftSession(requireNotNull(id)) == id
                            val stillCurrent = materialized && chatService.screen.value.openSessionId == id
                            if (id == null || edit == null || !stillCurrent) {
                                false
                            } else {
                                val actual =
                                    withContext(Dispatchers.IO) {
                                        edit.saveSessionConfig(
                                            id,
                                            com.helix.core.policy.SessionPermissionConfig
                                                .of(selected),
                                            System.currentTimeMillis(),
                                        )
                                        checkNotNull(edit.activeConfigFor(id)).mode
                                    }
                                if (chatService.screen.value.openSessionId == id) {
                                    permissionMode = actual
                                    permissionRevision++
                                }
                                actual == selected
                            }
                        },
                        onSelectModel = chatService::selectSessionModel,
                        onSetMode = chatService::setMode,
                        onCommandMode = { mode ->
                            sessionId?.let { chatService.setModeFromComposer(it, mode) } ?: false
                        },
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
            onSettings = onAgentDefaults,
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
            screen.sessionTitle,
            onDismiss = { renameId = null },
            onSave = {
                chatService.renameSession(id, it)
                renameId = null
            },
        )
    }
    if (directoryOpen && fileManager != null) {
        SessionDirectoryDialog(fileManager, onDismiss = { directoryOpen = false }) {
            chatService.setSessionDirectory(it, screen.openSessionId).await()
        }
    }

    screen.pendingDisclosure?.let { summary ->
        DisclosureDialog(
            summary = summary,
            onConfirm = {
                val pending = chatService.pendingComposerSubmission()
                if (pending != null) {
                    val ownsOrdinaryComposer = pending.revisedMessageId == null
                    if (ownsOrdinaryComposer) buffer.sending = true
                    scope.launch {
                        try {
                            handleReceipt(chatService.confirmSubmission(pending).await())
                        } finally {
                            if (ownsOrdinaryComposer) buffer.sending = false
                        }
                    }
                } else {
                    val queued = chatService.pendingSessionInputResume()
                    if (queued != null) {
                        scope.launch {
                            val outcome = chatService.confirmSessionInputResume(queued.first, queued.second).await()
                            if (outcome is ChatSubmissionOutcome.Rejected) {
                                ChatSubmissionErrorMapper
                                    .mapReason(
                                        outcome.reason,
                                        context,
                                    )?.let(chatService::showBlockedReason)
                            }
                            inputQueueEpoch += 1
                        }
                    } else {
                        chatService.confirmSend()
                    }
                }
            },
            onDismiss = {
                val pending = chatService.pendingComposerSubmission()
                if (pending != null) {
                    scope.launch { handleReceipt(chatService.cancelSubmission(pending).await()) }
                } else {
                    val queued = chatService.pendingSessionInputResume()
                    if (queued != null) {
                        scope.launch {
                            chatService.cancelSessionInputResume(queued.first, queued.second).await()
                            inputQueueEpoch += 1
                        }
                    } else {
                        chatService.cancelPendingSend()
                    }
                }
            },
        )
    }
}
