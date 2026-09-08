package com.helix.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageUi
import com.helix.app.chat.SessionRowUi
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.voice.SpeechRecognitionLauncher
import com.helix.app.voice.VoiceInputMapper
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnState
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
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val sessions by chatService.sessions.collectAsStateWithLifecycle()
    val profile by chatService.profile.collectAsStateWithLifecycle()
    val runControl by chatService.runControl.collectAsStateWithLifecycle()
    val providerRows by providerService.rows.collectAsStateWithLifecycle()
    var newSessionOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val reminderGoal by chatService.reminderGoal.collectAsStateWithLifecycle()
    var goalsOpen by remember { mutableStateOf(false) }
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
            .testTag("screen-sessions"),
    ) {
        if (screen.openSessionId == null) {
            SessionListSection(
                sessions = sessions,
                onNew = { newSessionOpen = true },
                onOpen = { chatService.openSession(it) },
                onArchive = { chatService.archiveSession(it) },
            )
        } else {
            if (runControl.mode == AgentMode.GOAL) {
                TextButton(
                    enabled = true,
                    onClick = { goalsOpen = true },
                    modifier = Modifier.testTag("goal-manage"),
                ) {
                    Text(stringResource(R.string.goal_manage))
                }
            }
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
                        onSend = {
                            if (runControl.mode == AgentMode.GOAL) {
                                goalsOpen = true
                            } else {
                                chatService.send(input.trim())
                                input = ""
                            }
                        },
                        onStop = { chatService.stop() },
                        onRetry = { chatService.retry() },
                        onDismissBlocked = { chatService.dismissBlocked() },
                        onApproveApproval = { chatService.approveApproval(it) },
                        onDenyApproval = { chatService.denyApproval(it) },
                        onStageAttachment = { chatService.stageAttachment(it) },
                        onRemoveAttachment = { chatService.removePendingAttachment(it) },
                        onBindProvider = { row -> chatService.bindProviderToSession(row.id, row.model) },
                        onSetMode = chatService::setMode,
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

    if (newSessionOpen) {
        NewSessionDialog(
            providers = providerRows.filter { it.chatSelectable },
            onCreated = { title, row ->
                val id = chatService.createSession(title, row.id, row.model)
                newSessionOpen = false
                chatService.openSession(id)
            },
            onDismiss = { newSessionOpen = false },
        )
    }

    screen.pendingDisclosure?.let { summary ->
        DisclosureDialog(
            summary = summary,
            onConfirm = { chatService.confirmSend() },
            onDismiss = { chatService.cancelPendingSend() },
        )
    }
}

// The Compose DSL keeps each section in one composable; detekt's LongMethod
// does not model UI composition well, so it is suppressed per composable
// (same convention as the app shell).
@Composable
@Suppress("FunctionName", "LongMethod")
private fun SessionListSection(
    sessions: List<SessionRowUi>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onArchive: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("chat-session-list"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.chat_session_header), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = onNew,
                    modifier = Modifier.testTag("chat-new-session"),
                ) {
                    Text(stringResource(R.string.chat_new_session))
                }
            }
        }
        if (sessions.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.chat_empty_sessions_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sessions, key = { it.id }) { session ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(
                    modifier =
                        Modifier
                            .clickable { onOpen(session.id) }
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(session.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            UiLabels.formatTime(session.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val noProvider = stringResource(R.string.chat_no_provider)
                    val archivedSuffix = stringResource(R.string.chat_archived_suffix)
                    Text(
                        buildString {
                            append(session.providerName ?: noProvider)
                            if (session.model != null) append(" · ${session.model}")
                            if (session.isArchived) append(archivedSuffix)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(
                            onClick = { onArchive(session.id) },
                            enabled = !session.isArchived,
                            modifier = Modifier.testTag("chat-archive"),
                        ) {
                            Text(stringResource(R.string.chat_archive))
                        }
                    }
                }
            }
        }
    }
}

// The create race (provider became untested between render and click) is the
// ONLY expected exception on this path; its internal message is never shown
// raw (doc 02 section 13), so the catch is an intentional, documented no-op
// beyond setting the user-visible error state.
@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException")
private fun NewSessionDialog(
    providers: List<ProviderRowUi>,
    onCreated: suspend (String, ProviderRowUi) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val createFailed = stringResource(R.string.chat_create_failed_provider_unavailable)
    val canCreate = title.isNotBlank() && selected != null && providers.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_new_session)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.chat_session_title_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("chat-new-session-title"),
                )
                if (providers.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_no_provider_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    providers.forEach { row ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = row.id }
                                    .testTag("chat-new-session-provider"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == row.id,
                                onClick = { selected = row.id },
                            )
                            Text(
                                stringResource(R.string.chat_provider_option, row.displayName, row.model),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            // The create runs on the dialog's coroutine scope: the Room write
            // happens on the chat service's IO scope (createSession is
            // suspend), never on this UI thread.
            TextButton(
                enabled = canCreate,
                onClick = {
                    val row = providers.firstOrNull { it.id == selected }
                    if (row == null) return@TextButton
                    scope.launch {
                        try {
                            onCreated(title.trim(), row)
                        } catch (e: IllegalArgumentException) {
                            // The create `require(...)` fires only on a race
                            // (the provider became untested between render and
                            // click); the internal English message is never
                            // shown raw (doc 02 section 13).
                            error = createFailed
                        }
                    }
                },
                modifier = Modifier.testTag("chat-new-session-confirm"),
            ) {
                Text(stringResource(R.string.chat_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("chat-new-session-cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        modifier = Modifier.testTag("chat-new-session-dialog"),
    )
}

/** The conversation's intents, bundled so the composable stays within the parameter budget. */
data class ConversationIntents(
    val onBack: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissBlocked: () -> Unit,
    val onApproveApproval: (String) -> Unit,
    val onDenyApproval: (String) -> Unit,
    val onStageAttachment: (String) -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    /** HXA-056: bind a tested provider to the open (provider-free) draft session. */
    val onBindProvider: (ProviderRowUi) -> Unit,
    val onSetMode: (AgentMode) -> Unit,
    val onSetChatTools: (Boolean) -> Unit,
    val onRecoverSubscriptionResult: (String, String) -> Unit = { _, _ -> },
    val onInspectSubscription: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onRecoverProot: (String, String) -> Unit = { _, _ -> },
    val onRetryProotAck: (String, String) -> Unit = { _, _ -> },
    val onInspectProot: (String, String, Boolean) -> Unit = { _, _, _ -> },
)

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
private fun ConversationSection(
    screen: ChatScreenState,
    profile: SafetyProfile,
    runControl: RunControlConfig,
    input: String,
    onInput: (String) -> Unit,
    bindableProviders: List<ProviderRowUi>,
    intents: ConversationIntents,
) {
    // The document picker (HXA-049): picking a document NEVER sends — it only stages the
    // one-time private copy through [ConversationIntents.onStageAttachment]. A null result
    // (the user backed out) is ignored.
    val attachmentPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) intents.onStageAttachment(uri.toString())
        }

    // HXA-067 voice input: the system recognizer (ACTION_RECOGNIZE_SPEECH) transcribes a
    // USER-INITIATED recording into an EDITABLE composer draft. It never auto-sends (the text only
    // lands in `input`; send is the explicit button) and never listens in the background (the
    // system UI records; we only receive the transcript on return). A cancel, no-match or failed
    // result is a benign no-draft (the system UI already surfaced it); a device with no recognizer
    // is gated pre-launch and shows a transient, path-free notice.
    val context = LocalContext.current
    val speech = remember { SpeechRecognitionLauncher() }
    var voiceDraft by remember { mutableStateOf<String?>(null) }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    val voiceUnavailable = stringResource(R.string.chat_voice_unavailable)
    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (val outcome = speech.mapResult(result.resultCode, result.data)) {
                is VoiceInputMapper.Outcome.Draft -> voiceDraft = outcome.text

                // Cancelled: the user cancelled or the recognizer returned no transcript — a
                // benign no-draft (the system UI already showed the cancel/error); never a send.
                else -> Unit
            }
        }

    // Apply a recognised draft to the composer ONCE, appended to whatever is already there
    // (reading the current `input` from this composition, never a stale closure).
    LaunchedEffect(voiceDraft) {
        val draft = voiceDraft
        if (draft != null) {
            onInput(if (input.isEmpty()) draft else "$input $draft")
            voiceDraft = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        AdaptiveConversationHeader(
            summary = "${runControl.mode} · ${screen.badge?.model.orEmpty()}",
            onBack = intents.onBack,
        ) {
            ModeControlSection(runControl, screen.isSending, intents)
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = intents.onBack, modifier = Modifier.testTag("chat-back")) {
                        Text(stringResource(R.string.chat_back_to_sessions))
                    }
                    Text(
                        if (profile == SafetyProfile.ADVANCED) {
                            stringResource(R.string.chat_profile_advanced)
                        } else {
                            stringResource(R.string.chat_profile_standard)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat-profile"),
                    )
                }
                screen.badge?.let { badge ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ExpandableSummary(
                            "${badge.model} · ${badge.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            tag = "chat-provider-summary",
                        )
                        Text(
                            "${UiLabels.displayOrigin(badge.origin)} · " +
                                stringResource(UiLabels.residenceLabelRes(badge.residence)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (badge.chips.isNotEmpty()) {
                            // Compose: resolve each chip label in a `for` loop (composable scope); a
                            // `joinToString` transform lambda is not composable and would not compile.
                            val chipLabels = mutableListOf<String>()
                            for (chip in badge.chips) {
                                chipLabels.add(localizedString(chip.res, chip.args))
                            }
                            Text(
                                chipLabels.joinToString("  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (screen.badge == null) {
                    // HXA-056: the open session has NO provider (a share-draft session) — offer
                    // the explicit bind so the draft can be reviewed and sent; binding never
                    // swaps an already-bound session's target (storage fails closed).
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.chat_unbound_provider),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("chat-unbound-provider"),
                        )
                        bindableProviders.forEach { row ->
                            TextButton(
                                onClick = { intents.onBindProvider(row) },
                                modifier = Modifier.testTag("chat-bind-provider"),
                            ) {
                                Text(stringResource(R.string.chat_provider_option, row.displayName, row.model))
                            }
                        }
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
        val emptyConversation =
            screen.activeTurn == null &&
                listOf(screen.messages, screen.toolTimeline, screen.subscriptionRecoveries).all { it.isEmpty() }
        ConversationTimeline(
            sessionId = screen.openSessionId,
            followContent = !emptyConversation,
            contentVersion =
                listOf(
                    screen.messages,
                    screen.subscriptionRecoveries,
                    screen.toolTimeline,
                    screen.activeTurn,
                ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (emptyConversation) {
                item(key = "empty-conversation") {
                    EmptyConversationHint(runControl.mode == AgentMode.GOAL, screen.badge != null)
                }
            }
            items(screen.messages, key = { it.id }) { message ->
                MessageRow(message)
            }
            items(screen.subscriptionRecoveries, key = { "subscription-${it.modelCallId}" }) { row ->
                SubscriptionRecoveryActions(row, intents.onInspectSubscription, intents.onRecoverSubscriptionResult)
            }
            items(screen.toolTimeline, key = { "tool-${it.turnId}-${it.callId}" }) { row ->
                ToolTimelineItem(row, intents)
            }
            val turn = screen.activeTurn
            if (turn != null && (!turn.state.isTerminal || turn.state == TurnState.CANCELLED)) {
                item(key = "streaming") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!turn.state.isTerminal && !turn.streamingText.isNullOrBlank()) {
                            MessageRow(MessageUi("streaming", "assistant", turn.streamingText.orEmpty()))
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
                        if (screen.retryTargetTurnId != null) {
                            TextButton(onClick = intents.onRetry, modifier = Modifier.testTag("chat-retry")) {
                                Text(stringResource(R.string.chat_retry))
                            }
                        }
                    }
                }
            }
        }
        if (screen.pendingAttachments.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("chat-pending-attachments"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                screen.pendingAttachments.forEach { attachment ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(attachment.fileName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                UiLabels.formatBytes(attachment.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { intents.onRemoveAttachment(attachment.id) },
                            modifier = Modifier.testTag("chat-pending-remove-${attachment.id}"),
                        ) {
                            Text(stringResource(R.string.chat_delete_attachment))
                        }
                    }
                }
            }
        }
        voiceNotice?.let { notice ->
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
        ConversationComposer(
            input = input,
            onInput = onInput,
            isSending = screen.isSending,
            hasAttachments = screen.pendingAttachments.isNotEmpty(),
            goalMode = runControl.mode == AgentMode.GOAL,
            actions =
                ComposerActions(
                    onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                    onVoice = {
                        when (VoiceInputMapper.preCheck(speech.isAvailable(context))) {
                            VoiceInputMapper.Outcome.Available -> {
                                voiceNotice = null
                                voiceLauncher.launch(speech.buildIntent(context))
                            }

                            else -> {
                                voiceNotice = voiceUnavailable
                            }
                        }
                    },
                    onSend = intents.onSend,
                    onStop = intents.onStop,
                ),
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun ModeControlSection(
    config: RunControlConfig,
    turnActive: Boolean,
    intents: ConversationIntents,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("chat-mode-control"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AgentMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.mode == mode,
                    onClick = { if (config.mode != mode) intents.onSetMode(mode) },
                    enabled = !turnActive,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("chat-mode-${mode.name.lowercase()}"),
                    label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        Text(
            stringResource(modeExplanation(config.mode)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chat-mode-explanation"),
        )
        if (config.mode == AgentMode.CHAT) {
            TextButton(
                onClick = { intents.onSetChatTools(!config.chatToolsEnabled) },
                enabled = !turnActive,
                modifier = Modifier.testTag("chat-tools-toggle"),
            ) {
                Text(
                    stringResource(
                        if (config.chatToolsEnabled) R.string.chat_tools_disable else R.string.chat_tools_enable,
                    ),
                )
            }
        }
        Text(
            stringResource(
                R.string.chat_budget_summary,
                config.budgets.maxSteps,
                config.budgets.maxModelCalls,
                config.budgets.maxOutputTokens,
                config.budgets.maxTotalTokens,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("chat-budget-summary"),
        )
    }
}

private fun modeExplanation(mode: AgentMode): Int =
    when (mode) {
        AgentMode.CHAT -> R.string.chat_mode_chat_explanation
        AgentMode.PLAN -> R.string.chat_mode_plan_explanation
        AgentMode.ACT -> R.string.chat_mode_act_explanation
        AgentMode.GOAL -> R.string.chat_mode_goal_explanation
    }

/**
 * One tool-timeline row (roadmap HXA-036): the tool REQUEST + RESULT, and — while the
 * approval card is live — the full [ApprovalCard] confirmation surface. The four timeline
 * message types (model text, tool request, tool result, approval card) are visually
 * distinct here (doc 01 FR-CHAT-003).
 */
@Composable
@Suppress("FunctionName")
internal fun ToolTimelineItem(
    row: com.helix.app.chat.ToolTimelineRow,
    intents: ConversationIntents,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .testTag("tool-row-${row.callId}"),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.chat_tool_row, row.toolName),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                row.stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tool-row-state-${row.callId}"),
            )
        }
        ExpandableSummary(
            stringResource(R.string.chat_tool_request, row.requestSummary),
            style = MaterialTheme.typography.bodySmall,
            tag = "tool-row-args-${row.callId}",
            collapsedLines = 3,
        )
        row.resultSummary?.let { summary ->
            ExpandableSummary(
                stringResource(R.string.chat_tool_result, summary),
                style = MaterialTheme.typography.bodySmall,
                tag = "tool-row-result-${row.callId}",
                collapsedLines = 5,
            )
        }
        if (row.prootRecoveryAvailable) {
            ProotRecoveryActions(row, intents.onInspectProot, intents.onRecoverProot, intents.onRetryProotAck)
        }
        row.card?.let { card ->
            ApprovalCard(
                card = card,
                onApprove = { intents.onApproveApproval(card.approvalId) },
                onDeny = { intents.onDenyApproval(card.approvalId) },
            )
        }
    }
}

/** One message bubble; the alignment branch is a plain Row (no scope tricks). */
@Composable
@Suppress("FunctionName")
private fun MessageRow(message: MessageUi) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            modifier = Modifier.testTag(if (isUser) "chat-message-user" else "chat-message-assistant"),
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun ProotRecoveryActions(
    row: com.helix.app.chat.ToolTimelineRow,
    action: (String, String, Boolean) -> Unit,
    recover: (String, String) -> Unit = { _, _ -> },
    retryAcknowledgement: (String, String) -> Unit = { _, _ -> },
) {
    if (row.prootRecoveredOutput == null) row.prootRecoveryReport?.let { Text(stringResource(it.labelRes)) }
    TextButton(
        enabled = !row.prootRecoveryBusy,
        onClick = { recover(row.turnId, row.callId) },
        modifier = Modifier.testTag("proot-result-${row.callId}"),
    ) { Text(stringResource(R.string.proot_recovery_view)) }
    if (row.prootResultUnavailable) Text(stringResource(R.string.proot_recovery_result_unavailable))
    row.prootRecoveredOutput?.let {
        ProotResultPanel(it, row.callId)
        ProotAcknowledgementActions(row, it.acknowledged, retryAcknowledgement)
    }
    TextButton(
        enabled = !row.prootRecoveryBusy,
        onClick = { action(row.turnId, row.callId, false) },
        modifier = Modifier.testTag("proot-query-${row.callId}"),
    ) { Text(stringResource(R.string.proot_recovery_query)) }
    if (row.prootRecoveryReport?.canStop == true) {
        TextButton(
            enabled = !row.prootRecoveryBusy,
            onClick = { action(row.turnId, row.callId, true) },
            modifier = Modifier.testTag("proot-stop-${row.callId}"),
        ) { Text(stringResource(R.string.proot_recovery_stop)) }
    }
}
