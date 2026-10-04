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
import com.helix.app.voice.SpeechRecognitionLauncher
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
    composerAvailability: ComposerAvailability = ComposerAvailability(),
    composerStatus: @Composable () -> Unit = {},
    composerFeedback: @Composable () -> Unit = {},
    composerOptions: @Composable () -> Unit = {},
    artifacts: @Composable () -> Unit = {},
    modelSourceGroups: List<ProviderProvisioningKind> =
        listOf(
            ProviderProvisioningKind.USER_CONFIGURED,
            ProviderProvisioningKind.ON_DEVICE_ASSET,
        ),
) {
    val context = LocalContext.current
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

    // HXA-067 voice input: the system recognizer (ACTION_RECOGNIZE_SPEECH) transcribes a
    // USER-INITIATED recording into an EDITABLE composer draft. It never auto-sends (the text only
    // lands in `input`; send is the explicit button) and never listens in the background (the
    // system UI records; we only receive the transcript on return). A cancel, no-match or failed
    // result is a benign no-draft (the system UI already surfaced it); a device with no recognizer
    // is gated pre-launch and shows a transient, path-free notice.
    val speech = remember { SpeechRecognitionLauncher() }
    var voiceTarget by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    val currentVoiceSession by androidx.compose.runtime.rememberUpdatedState(screen.openSessionId)
    val currentVoiceInput by androidx.compose.runtime.rememberUpdatedState(input)
    val currentVoiceEdit by androidx.compose.runtime.rememberUpdatedState(onInput)
    var inputNotice by remember { mutableStateOf<String?>(null) }
    val voiceUnavailable = stringResource(R.string.chat_voice_unavailable)
    val captureUnavailable = stringResource(R.string.chat_capture_unavailable)
    val pickerUnavailable = stringResource(R.string.chat_picker_unavailable)
    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val target = voiceTarget
            voiceTarget = null
            val outcome = speech.mapResult(result.resultCode, result.data)
            if (outcome is VoiceInputMapper.Outcome.Draft && target != null && target == currentVoiceSession) {
                currentVoiceEdit(VoiceInputMapper.appendDraft(currentVoiceInput, outcome.text))
            }
        }

    val timelineListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val searchController = remember { ConversationSearchController() }
    var searchActive by remember { mutableStateOf(false) }

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
        ) {
            FlowRow {
                if (!screen.isDraft) {
                    TextButton(intents.onRename) { Text(stringResource(R.string.chat_rename)) }
                    intents.onExport?.let { export ->
                        TextButton(export, modifier = Modifier.testTag("session-export-open")) {
                            Text(stringResource(R.string.session_export_title))
                        }
                    }
                }
                TextButton(intents.onDirectory, enabled = !screen.isSending) {
                    Text(stringResource(R.string.chat_directory))
                }
            }
            if (screen.directoryRef != null) {
                TextButton(intents.onGit, modifier = Modifier.testTag("session-git-open")) {
                    Text(stringResource(R.string.nav_git))
                }
            }
            screen.directoryRef?.let { Text(it) }
        }
        if (searchActive) {
            ConversationSearchBar(
                query = searchController.query,
                matchCount = searchController.matches.size,
                currentMatchIndex = searchController.currentMatchIndex,
                onQueryChange = { q ->
                    searchController.onQueryChange(q, screen)
                    searchController.currentMatch?.let { match ->
                        coroutineScope.launch { timelineListState.animateScrollToItem(match.listIndex) }
                    }
                },
                onPrevMatch = {
                    searchController.prevMatch()?.let { match ->
                        coroutineScope.launch { timelineListState.animateScrollToItem(match.listIndex) }
                    }
                },
                onNextMatch = {
                    searchController.nextMatch()?.let { match ->
                        coroutineScope.launch { timelineListState.animateScrollToItem(match.listIndex) }
                    }
                },
                onClose = {
                    searchActive = false
                    searchController.clear()
                },
            )
        }
        if (runControl.mode == AgentMode.GOAL && !screen.isDraft) {
            TextButton(intents.onManageGoal, modifier = Modifier.testTag("goal-manage")) {
                Text(stringResource(R.string.goal_manage))
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
        TaskLedgerCard(screen.taskLedger, screen.openSessionId)
        if (screen.workspaceRecovered) {
            Column(Modifier.padding(horizontal = 12.dp).testTag("workspace-recovery-notice")) {
                Text(stringResource(R.string.workspace_recovered_notice), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = intents.onDirectory) { Text(stringResource(R.string.chat_directory_choose)) }
            }
        }
        if (screen.isFork) {
            Text(
                stringResource(R.string.session_fork_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("session-fork-notice"),
            )
        }
        val emptyConversation =
            screen.activeTurn == null &&
                listOf(screen.messages, screen.toolTimeline, screen.subscriptionRecoveries, screen.taskLedger)
                    .all { it.isEmpty() }
        ConversationTimeline(
            sessionId = screen.openSessionId,
            followContent = !emptyConversation && !searchActive,
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
            if (emptyConversation) {
                item(key = "empty-conversation") {
                    EmptyConversationHint(
                        goalMode = runControl.mode == AgentMode.GOAL,
                        hasProvider = screen.badge != null,
                        onSelectPrompt = onInput,
                    )
                }
            }
            val currentTargetId = searchController.currentMatch?.targetId.takeIf { searchActive }
            val activeSearchQuery = searchController.query.takeIf { searchActive && it.isNotBlank() }

            com.helix.app.chat.conversationEntries(screen).forEach { entry ->
                val forkMessageId = entry.forkMessageId(screen)
                items(entry.initialMessages, key = { it.id }) {
                    MessageRow(
                        it,
                        null,
                        intents.onEditLatest?.takeIf { _ ->
                            it.id == screen.messages.lastOrNull { message -> message.role == "user" }?.id
                        },
                        searchQuery = activeSearchQuery,
                        isCurrentMatch = it.id == currentTargetId,
                    )
                }
                item(key = "operations-${entry.key}") {
                    TurnOperations(
                        entry = entry,
                        intents = intents,
                        activeCallId = currentTargetId,
                    )
                }
                items(entry.followingMessages, key = { it.id }) {
                    MessageRow(
                        message = it,
                        onFork = intents.onFork?.takeIf { _ -> it.id == forkMessageId },
                        onEdit =
                            intents.onEditLatest?.takeIf { _ ->
                                it.role == "user" &&
                                    it.id == screen.messages.lastOrNull { message -> message.role == "user" }?.id
                            },
                        onRegenerate =
                            intents.onRegenerateLatest?.takeIf { _ ->
                                !screen.isSending && it.role == "assistant" &&
                                    it.id == screen.messages.lastOrNull { m -> m.role == "assistant" }?.id
                            },
                        searchQuery = activeSearchQuery,
                        isCurrentMatch = it.id == currentTargetId,
                    )
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
                            panel = pastPanel,
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
                                searchQuery = activeSearchQuery,
                                isCurrentMatch = "streaming" == currentTargetId,
                            )
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
                            panel = activePanel,
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
        artifacts()
        PendingAttachmentStrip(screen.pendingAttachments, intents.onRemoveAttachment)
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
        var modelPickerRequest by remember(screen.openSessionId) { mutableStateOf(0) }
        ConversationComposer(
            editorKey = screen.openSessionId,
            onChooseModel = { modelPickerRequest++ },
            optionsContent = composerOptions,
            input = input,
            onInput = onInput,
            isSending = screen.isSending,
            hasAttachments = screen.pendingAttachments.isNotEmpty(),
            referenceLabel = referenceLabel,
            onRemoveReference = intents.onClearReference,
            goalMode = runControl.mode == AgentMode.GOAL,
            mode = runControl.mode,
            onMode = intents.onCommandMode,
            modelSelector = {
                ComposerModelMenu(
                    bindableProviders,
                    screen.badge?.providerId,
                    screen.badge?.model,
                    !screen.isSending && screen.pendingDisclosure == null,
                    intents.onSelectModel,
                    onManageModels = intents.onManageModels,
                    sourceGroups = modelSourceGroups,
                    onConfigureSource = intents.onConfigureModelSource,
                    openRequest = modelPickerRequest,
                    reasoningContent = {
                        ComposerReasoningMenu(
                            runControl.reasoning,
                            screen.badge?.reasoningSupported == true && !screen.isSending,
                            intents.onSetReasoning,
                            efforts = screen.badge?.reasoningEfforts.orEmpty(),
                        )
                    },
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
                    onExpert = intents.onExpert,
                    onSkills = intents.onSkills,
                    onConnectors = intents.onConnectors,
                    onSessionSettings = intents.onSettings,
                    onVoice = {
                        when (VoiceInputMapper.preCheck(speech.isAvailable(context))) {
                            VoiceInputMapper.Outcome.Available -> {
                                inputNotice = null
                                voiceTarget = screen.openSessionId
                                try {
                                    voiceLauncher.launch(speech.buildIntent(context))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    inputNotice = voiceUnavailable
                                } catch (_: SecurityException) {
                                    inputNotice = voiceUnavailable
                                }
                            }

                            else -> {
                                inputNotice = voiceUnavailable
                            }
                        }
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
