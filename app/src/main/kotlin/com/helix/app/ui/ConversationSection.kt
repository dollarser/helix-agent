package com.helix.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessageUi
import com.helix.app.provider.ProviderRowUi
import com.helix.app.voice.VisibleReplyFrame
import com.helix.app.voice.VoiceInputMapper
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Renders one conversation from observable state and explicit UI intents. */

@Composable
// Explicit observable state, user intents and an optional application-owned presentation slot.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "LongParameterList", "TooGenericExceptionCaught")
internal fun ConversationSection(
    screen: ChatScreenState,
    runControl: RunControlConfig,
    input: String,
    onInput: (String) -> Unit,
    bindableProviders: List<ProviderRowUi>,
    intents: ConversationIntents,
    permissionMode: SessionPermissionMode? = null,
    referenceLabel: String? = null,
    expertName: String? = null,
    pluginNames: List<String> = emptyList(),
    knowledgeReferenceContent: @Composable () -> Unit = {},
    hasKnowledgeReferences: Boolean = false,
    composerAvailability: ComposerAvailability = ComposerAvailability(),
    composerStatus: @Composable () -> Unit = {},
    composerFeedback: @Composable () -> Unit = {},
    composerOptions: @Composable () -> Unit = {},
    artifacts: @Composable () -> Unit = {},
    taskPreparation: @Composable () -> Unit = {},
    modelSourceGroups: List<ProviderProvisioningKind> =
        listOf(
            ProviderProvisioningKind.USER_CONFIGURED,
            ProviderProvisioningKind.ON_DEVICE_ASSET,
        ),
) {
    val context = LocalContext.current
    val reader = (context.applicationContext as? com.helix.app.HelixApplication)?.readAloud
    ReadAloudLifecycle(reader, screen.openSessionId)
    val speechFrame = visibleReplyFrame(screen)
    ObserveLiveReplyReadAloud(reader, speechFrame)
    // The document picker (HXA-049): picking a document NEVER sends — it only stages the
    // one-time private copy through [ConversationIntents.onStageAttachment]. A null result
    // (the user backed out) is ignored.
    val pickerSession by androidx.compose.runtime.rememberUpdatedState(screen.openSessionId)
    var fileTarget by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    var photoTarget by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    var cameraTarget by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    val attachmentPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && fileTarget != null &&
                fileTarget == pickerSession
            ) {
                intents.onStageAttachment(uri.toString())
            }
            fileTarget = null
        }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && photoTarget != null &&
                photoTarget == pickerSession
            ) {
                intents.onStageAttachment(uri.toString())
            }
            photoTarget = null
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
            val file = cameraPath?.let(::File)
            if (captured && file != null && pickerMatches(cameraTarget, pickerSession)) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                intents.onStageAttachment(uri.toString())
            } else {
                file?.delete()
            }
            cameraPath = null
            cameraTarget = null
        }

    var voiceOpen by remember(screen.openSessionId) { mutableStateOf(false) }
    val voiceKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var voiceBase by remember(screen.openSessionId) { mutableStateOf("") }
    var voicePreview by remember(screen.openSessionId) { mutableStateOf<String?>(null) }
    var pendingFork by remember(screen.openSessionId) { mutableStateOf<MessageUi?>(null) }
    pendingFork?.let { message ->
        SessionForkConfirmation(
            message = message,
            onDismiss = { pendingFork = null },
            onConfirm = {
                // Clear before calling the service: repeated clicks cannot create another branch.
                if (pendingFork?.id == message.id && pickerSession == screen.openSessionId) {
                    pendingFork = null
                    intents.onFork?.invoke(message.id)
                }
            },
        )
    }
    var inputNotice by remember { mutableStateOf<String?>(null) }
    val captureUnavailable = stringResource(R.string.chat_capture_unavailable)
    val pickerUnavailable = stringResource(R.string.chat_picker_unavailable)

    val timelineListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val searchController = remember { ConversationSearchController() }
    var focusedRevision by remember(screen.openSessionId) { mutableStateOf(-1L) }
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(screen.historyFocusRevision, screen.messages) {
        val id = screen.focusedHistoryMessage
        if (id != null && focusedRevision != screen.historyFocusRevision && screen.messages.any { it.id == id }) {
            // Empty substring enumerates the displayed items using the same timeline-index mapping.
            val target = findMatches("", screen).firstOrNull { it.targetId == id }
            if (target != null) {
                searchController.updateMatches(screen)
                searchController.selectTarget(id)
                timelineListState.scrollToItem(target.listIndex)
                focusedRevision = screen.historyFocusRevision
            }
        }
    }
    var modelPickerRequest by remember(screen.openSessionId) { mutableStateOf(0) }
    val voiceDraftChangedNotice = stringResource(R.string.voice_inline_draft_changed)
    LaunchedEffect(input, voiceOpen) {
        if (voiceOpen && input != voiceBase) {
            voicePreview = null
            voiceOpen = false
            inputNotice = voiceDraftChangedNotice
        }
    }

    LaunchedEffect(screen, searchActive) {
        if (searchActive) {
            searchController.updateMatches(screen)
        }
    }

    Column(Modifier.fillMaxSize()) {
        AdaptiveConversationHeader(
            summary = screen.sessionTitle.ifBlank { stringResource(R.string.chat_new_session) },
            onNew = intents.onNew,
            onNavigation = intents.onNavigation,
            onRename = intents.onRename,
            onTasks = intents.onTasks,
            onSettings = intents.onSettings,
            onSearch = { searchActive = !searchActive },
        ) { closeDetails ->
            artifacts()
            TaskLedgerCard(screen.taskLedger, screen.openSessionId)
            if (runControl.mode == AgentMode.GOAL || screen.hasGoals) {
                ConversationMenuRow(
                    stringResource(R.string.goal_manage),
                    {
                        closeDetails()
                        intents.onManageGoal()
                    },
                    Modifier.testTag("goal-manage"),
                )
            }
            if (screen.isFork) {
                Text(
                    stringResource(R.string.session_fork_notice),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("session-fork-notice"),
                )
            }
            Column {
                if (!screen.isDraft) {
                    ConversationMenuRow(stringResource(R.string.chat_rename), {
                        closeDetails()
                        intents.onRename()
                    })
                    intents.onExport?.let { export ->
                        ConversationMenuRow(
                            stringResource(R.string.session_export_title),
                            {
                                closeDetails()
                                export()
                            },
                            Modifier.testTag("session-export-open"),
                        )
                    }
                }
                ConversationMenuRow(
                    stringResource(R.string.chat_directory),
                    {
                        closeDetails()
                        intents.onDirectory()
                    },
                    enabled = !screen.isSending,
                )
            }
            if (screen.directoryRef != null) {
                ConversationMenuRow(
                    stringResource(R.string.nav_git),
                    {
                        closeDetails()
                        intents.onGit()
                    },
                    Modifier.testTag("session-git-open"),
                )
            }
            screen.directoryRef?.let { ScopedPathText(it) }
        }
        if (searchActive) {
            ConversationSearchBar(
                query = searchController.query,
                matchCount =
                    if (screen.hasOlderMessages ||
                        screen.hasNewerMessages
                    ) {
                        -1
                    } else {
                        searchController.matches.size
                    },
                currentMatchIndex = searchController.currentMatchIndex,
                onQueryChange = { q ->
                    searchController.onQueryChange(q, screen)
                    intents.onFindHistoryMessage(if (searchController.matches.isEmpty()) q else "", null, null)
                    searchController.currentMatch?.let { match ->
                        coroutineScope.launch { timelineListState.animateScrollToItem(match.listIndex) }
                    }
                },
                onPrevMatch = {
                    if (searchController.currentMatchIndex == 0) {
                        val sequence =
                            screen.messages.find { it.id == searchController.currentMatch?.targetId }?.sequence
                                ?: screen.messages.firstOrNull()?.sequence
                        intents.onFindHistoryMessage(searchController.query, sequence, null)
                    }
                    searchController.prevMatch()?.let { match ->
                        coroutineScope.launch { timelineListState.animateScrollToItem(match.listIndex) }
                    }
                },
                onNextMatch = {
                    if (searchController.currentMatchIndex >= searchController.matches.lastIndex) {
                        val sequence =
                            screen.messages.find { it.id == searchController.currentMatch?.targetId }?.sequence
                                ?: screen.messages.lastOrNull()?.sequence
                        intents.onFindHistoryMessage(searchController.query, null, sequence)
                    }
                    searchController.nextMatch()?.let { match ->
                        coroutineScope.launch { timelineListState.animateScrollToItem(match.listIndex) }
                    }
                },
                onClose = {
                    searchActive = false
                    searchController.clear()
                    intents.onFindHistoryMessage("", null, null)
                },
            )
        }
        screen.modelBindingIssue?.let { issue ->
            Column(Modifier.fillMaxWidth().padding(8.dp).testTag("model-binding-issue")) {
                Text(stringResource(issue.messageResource), color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { modelPickerRequest++ }) {
                        Text(stringResource(R.string.chat_model_reselect))
                    }
                    TextButton(onClick = intents.onManageModels) {
                        Text(stringResource(R.string.chat_model_manage_source))
                    }
                }
            }
        }
        screen.blockedReason?.let { reason ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f).testTag("chat-blocked-reason"),
                )
                TextButton(onClick = intents.onDismissBlocked) { Text(stringResource(R.string.chat_blocked_dismiss)) }
            }
        }
        if (screen.workspaceRecovered) {
            Column(Modifier.padding(horizontal = 12.dp).testTag("workspace-recovery-notice")) {
                Text(stringResource(R.string.workspace_recovered_notice), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = intents.onDirectory) { Text(stringResource(R.string.chat_directory_choose)) }
            }
        }
        val emptyConversation =
            screen.activeTurn == null &&
                listOf(screen.messages, screen.toolTimeline, screen.subscriptionRecoveries, screen.taskLedger)
                    .all { it.isEmpty() }
        ConversationTimeline(
            sessionId = screen.openSessionId,
            followContent =
                !emptyConversation && !searchActive && screen.focusedHistoryMessage == null && !screen.hasNewerMessages,
            contentVersion =
                listOf(
                    screen.messages,
                    screen.subscriptionRecoveries,
                    screen.toolTimeline,
                    screen.activeTurn,
                ),
            state = timelineListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (screen.hasOlderMessages) {
                item(key = "older-messages") {
                    TextButton(
                        onClick = intents.onLoadOlderMessages,
                        modifier = Modifier.testTag("chat-load-older"),
                    ) {
                        Text(stringResource(R.string.chat_load_older_messages))
                    }
                }
            }
            if (screen.hasNewerMessages) {
                item(key = "latest-messages") {
                    TextButton(
                        onClick = intents.onShowLatestMessages,
                        modifier = Modifier.testTag("chat-show-latest"),
                    ) {
                        Text(stringResource(R.string.chat_show_latest_messages))
                    }
                }
            }
            if (emptyConversation) {
                item(key = "empty-conversation") {
                    EmptyConversationHint(
                        goalMode = runControl.mode == AgentMode.GOAL,
                        hasProvider = composerAvailability.modelSelected,
                        onSelectPrompt = { onInput(appendTaskPrompt(input, it)) },
                        onChooseModel = { modelPickerRequest++ },
                        preparation = taskPreparation,
                    )
                }
            }
            val currentTargetId =
                searchController.currentMatch?.targetId.takeIf { searchActive } ?: screen.focusedHistoryMessage
            val activeSearchQuery = searchController.query.takeIf { searchActive && it.isNotBlank() }

            com.helix.app.chat.conversationEntries(screen).forEach { entry ->
                val forkMessageId = entry.forkMessageId(screen)
                items(entry.timeline, key = { it.key }) { content ->
                    when (content) {
                        is com.helix.app.chat.ConversationContent.Message -> {
                            val message = content.value
                            MessageRow(
                                message = message,
                                onOpenTaskResult = intents.onOpenTaskResult,
                                onSaveReply =
                                    intents.onSaveReply?.takeIf {
                                        message.role == "assistant" &&
                                            !screen.isSending
                                    },
                                imageContent = { ChatMessageImages(message.images, intents.onPreviewImage) },
                                onFork =
                                    intents.onFork?.takeIf { message.id == forkMessageId }?.let {
                                        { _: String -> pendingFork = message }
                                    },
                                onEdit =
                                    intents.onEditMessage?.takeIf {
                                        message.role == "user" && !screen.isSending && screen.pendingDisclosure == null
                                    },
                                onRegenerate =
                                    intents.onRegenerateLatest?.takeIf {
                                        !screen.isSending && message.role == "assistant" &&
                                            message.id == screen.messages.lastOrNull { it.role == "assistant" }?.id
                                    },
                                searchQuery = activeSearchQuery,
                                isCurrentMatch = message.id == currentTargetId,
                            )
                        }

                        is com.helix.app.chat.ConversationContent.Tool -> {
                            ToolTimelineItem(
                                content.value,
                                intents,
                                content.value.callId == currentTargetId,
                            )
                        }
                    }
                }
                if (entry.recoveries.isNotEmpty()) {
                    item(key = "operations-${entry.key}") {
                        TurnOperations(entry.copy(tools = emptyList()), intents, currentTargetId)
                    }
                }
                val past = screen.turns.firstOrNull { it.id == entry.key && it.id != screen.activeTurn?.id }
                if (past?.state == TurnState.FAILED && past.errorLabel != null) {
                    item(key = "error-${entry.key}") {
                        Text(
                            stringResource(R.string.chat_turn_failed, past.errorLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("chat-turn-error-${entry.key}"),
                        )
                    }
                }
                // HXA-204 slice 2: the settled turn's recovery panel (the active turn's panel is
                // emitted separately below — never both, or the list keys collide).
                val pastPanel =
                    if (entry.key != screen.activeTurn?.id) screen.recoveryPanels[entry.key] else null
                if (pastPanel != null) {
                    item(key = "recovery-${entry.key}") {
                        TurnRecoveryPanel(
                            panel =
                                pastPanel.copy(
                                    inlineNoticeShown =
                                        entry.messages.any {
                                            it.interruptionNotice !=
                                                null
                                        },
                                ),
                            busy = screen.recoveryBusy,
                            onReconnect = intents.onRecoveryReconnect,
                            onQueryResult = intents.onRecoveryQueryResult,
                            onGrantPermission = intents.onRecoveryGrantPermission,
                            onContinueGoal = intents.onRecoveryContinueGoal,
                            onRetry = intents.onRecoveryRetry,
                        )
                    }
                }
            }
            val turn = screen.activeTurn
            if (turn != null && (!turn.state.isTerminal || turn.state == TurnState.CANCELLED)) {
                item(key = "streaming") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!turn.state.isTerminal && !turn.streamingText.isNullOrBlank()) {
                            MessageRow(
                                MessageUi("streaming", "assistant", turn.streamingText.orEmpty()),
                                readAloud = false,
                                searchQuery = activeSearchQuery,
                                isCurrentMatch = "streaming" == currentTargetId,
                            )
                            if (speechFrame?.phase == VisibleReplyFrame.Phase.STREAMING && reader != null) {
                                ReadAloudAction(speechFrame.speechId, speechFrame.text, reader) {
                                    reader.startLiveReply(speechFrame)
                                }
                            }
                        }
                        TurnProgressLabel(
                            turn.state,
                            awaitingApproval =
                                screen.toolTimeline.any {
                                    it.turnId == turn.id && it.card?.state == ApprovalCardState.PENDING
                                },
                        )
                    }
                }
            }
            if (turn != null && turn.state == TurnState.FAILED && turn.errorLabel != null) {
                item(key = "turn-error") {
                    Column {
                        Text(
                            stringResource(R.string.chat_turn_failed, turn.errorLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("chat-turn-error"),
                        )
                        turn.budgetDetail?.let { Text(it, modifier = Modifier.testTag("chat-budget-detail")) }
                    }
                }
            }
            // HXA-204 slice 2: the active turn's recovery panel (FAILED shows the retry inside
            // it under the same `chat-retry` identity; INTERRUPTED / CANCELLED explain their
            // outcome and offer their own operations).
            if (turn != null) {
                val activePanel = screen.recoveryPanels[turn.id]
                if (activePanel != null) {
                    item(key = "recovery-${turn.id}") {
                        TurnRecoveryPanel(
                            panel =
                                activePanel.copy(
                                    inlineNoticeShown =
                                        screen.messages.any {
                                            it.turnId == turn.id && it.interruptionNotice != null
                                        },
                                ),
                            busy = screen.recoveryBusy,
                            onReconnect = intents.onRecoveryReconnect,
                            onQueryResult = intents.onRecoveryQueryResult,
                            onGrantPermission = intents.onRecoveryGrantPermission,
                            onContinueGoal = intents.onRecoveryContinueGoal,
                            onRetry = intents.onRecoveryRetry,
                        )
                    }
                }
            }
        }
        inputNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
                        .testTag("chat-voice-notice"),
            )
        }
        composerFeedback()
        composerStatus()
        ConversationComposer(
            referenceContent = knowledgeReferenceContent,
            voiceActive = voiceOpen,
            voiceContent = {
                androidx.compose.runtime.key(screen.openSessionId) {
                    if (voiceOpen) {
                        OfflineAsrGate(onDismiss = {
                            voicePreview = null
                            voiceOpen = false
                        }, onError = { inputNotice = it }) {
                            InlineVoiceInput(
                                onDismiss = {
                                    voicePreview = null
                                    voiceOpen = false
                                },
                                onAccept = { text -> onInput(VoiceInputMapper.appendDraft(voiceBase, text)) },
                                onPreview = { text -> voicePreview = VoiceInputMapper.appendDraft(voiceBase, text) },
                                onError = { inputNotice = it },
                            )
                        }
                    }
                }
            },
            attachmentsContent = {
                PendingAttachmentStrip(
                    screen.pendingAttachments,
                    intents.onRemoveAttachment,
                    screen.openSessionId,
                    intents.onPreviewImage,
                )
            },
            editorKey = screen.openSessionId,
            onChooseModel = { modelPickerRequest++ },
            optionsContent = {
                composerOptions()
            },
            input = voicePreview ?: input,
            onInput = onInput,
            isSending = screen.isSending,
            hasAttachments = screen.pendingAttachments.isNotEmpty(),
            hasKnowledgeReferences = hasKnowledgeReferences,
            referenceLabel = referenceLabel,
            expertName = expertName,
            pluginNames = pluginNames,
            onRemoveReference = intents.onClearReference,
            goalMode = runControl.mode == AgentMode.GOAL,
            mode = runControl.mode,
            onMode = intents.onCommandMode,
            modelSelector = {
                ComposerModelMenu(
                    bindableProviders,
                    screen.selectedModel?.providerId,
                    screen.selectedModel?.model,
                    !screen.isSending && screen.pendingDisclosure == null,
                    intents.onSaveModel,
                    onManageModels = intents.onManageModels,
                    sourceGroups = modelSourceGroups,
                    onConfigureSource = intents.onConfigureModelSource,
                    onRefreshModels = intents.onRefreshModels,
                    openRequest = modelPickerRequest,
                    reasoning = runControl.reasoning,
                    sessionKey = screen.openSessionId,
                    onDetectReasoning = intents.onDetectReasoning,
                )
            },
            permissionMode = permissionMode,
            onPermission = intents.onSettings,
            onPermissionMode = intents.onSelectPermission,
            contextUsage = screen.contextUsage,
            onCompact = intents.onCompact,
            canCompact =
                !screen.isDraft && !screen.isSending &&
                    screen.pendingDisclosure == null && screen.pendingAttachments.isEmpty() &&
                    composerAvailability.modelSelected,
            turnState = screen.activeTurn?.state,
            availability = composerAvailability,
            actions =
                ComposerActions(
                    onFile = {
                        fileTarget = screen.openSessionId
                        inputNotice = null
                        if (!launchExternalUi { attachmentPicker.launch(arrayOf("*/*")) }) {
                            fileTarget = null
                            inputNotice = pickerUnavailable
                        }
                    },
                    onPhoto = {
                        photoTarget = screen.openSessionId
                        inputNotice = null
                        if (!launchExternalUi { photoPicker.launch(arrayOf("image/*")) }) {
                            photoTarget = null
                            inputNotice = pickerUnavailable
                        }
                    },
                    onCamera = {
                        cameraTarget = screen.openSessionId
                        coroutineScope.launch {
                            try {
                                val capture =
                                    withContext(Dispatchers.IO) {
                                        val directory = File(context.filesDir, "attachments/camera")
                                        check(directory.mkdirs() || directory.isDirectory)
                                        val file = File.createTempFile("helix-camera-", ".jpg", directory)
                                        val uri =
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file,
                                            )
                                        file to uri
                                    }
                                cameraPath = capture.first.path
                                cameraLauncher.launch(capture.second)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // User-initiated camera boundary: preserve the draft and show no raw platform error.
                                cameraPath?.let { File(it).delete() }
                                cameraPath = null
                                cameraTarget = null
                                inputNotice = captureUnavailable
                            }
                        }
                    },
                    onReference = intents.onReference,
                    onKnowledgeReference = intents.onKnowledgeReference,
                    onExpert = intents.onExpert,
                    onSkills = intents.onSkills,
                    onConnectors = intents.onConnectors,
                    onVoice = {
                        voiceKeyboard?.hide()
                        reader?.leave()
                        inputNotice = null
                        voiceBase = input
                        voicePreview = input
                        voiceOpen = true
                    },
                    onSend = intents.onSend,
                    onStop = {
                        val turnId = screen.activeTurn?.id
                        if (turnId != null && intents.onStopTurn != null) {
                            intents.onStopTurn.invoke(turnId)
                        } else {
                            intents.onStop()
                        }
                    },
                ),
        )
    }
}

internal fun pickerMatches(
    target: String?,
    current: String?,
): Boolean = target != null && target == current
