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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessageUi
import com.helix.app.provider.ProviderRowUi
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.voice.SpeechRecognitionLauncher
import com.helix.app.voice.VoiceInputMapper
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnState
import kotlinx.coroutines.launch

/** Renders one conversation from observable state and explicit UI intents. */

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ConversationSection(
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
            summary = screen.sessionTitle.ifBlank { stringResource(R.string.chat_new_session) },
            onBack = intents.onBack,
            onNew = intents.onNew,
            onNavigation = intents.onNavigation,
            onRename = intents.onRename,
            onTasks = intents.onTasks,
        ) {
            FlowRow {
                if (!screen.isDraft) {
                    TextButton(intents.onRename) { Text(stringResource(R.string.chat_rename)) }
                }
                TextButton(intents.onDirectory, enabled = !screen.isSending) {
                    Text(stringResource(R.string.chat_directory))
                }
            }
            screen.directoryRef?.let { Text(it) }
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
                        if (bindableProviders.isEmpty()) Text(stringResource(R.string.chat_no_provider_available))
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
        TaskLedgerCard(screen.taskLedger)
        val emptyConversation =
            screen.activeTurn == null &&
                listOf(screen.messages, screen.toolTimeline, screen.subscriptionRecoveries, screen.taskLedger)
                    .all { it.isEmpty() }
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
            com.helix.app.chat.conversationEntries(screen).forEach { entry ->
                items(entry.messages.filter { it.role == "user" }, key = { it.id }) { MessageRow(it) }
                item(key = "operations-${entry.key}") {
                    TurnOperations(entry, screen.activeTurn, intents)
                }
                items(entry.messages.filter { it.role != "user" }, key = { it.id }) { MessageRow(it) }
                val past = screen.turns.firstOrNull { it.id == entry.key && it.id != screen.activeTurn?.id }
                if (past?.state == TurnState.FAILED && past.errorLabel != null) {
                    item(key = "error-${entry.key}") {
                        Text(stringResource(R.string.chat_turn_failed, past.errorLabel))
                    }
                }
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
            mode = runControl.mode,
            onMode = intents.onSetMode,
            modelSelector = {
                ComposerModelMenu(
                    bindableProviders,
                    screen.badge?.providerId,
                    screen.badge?.model,
                    !screen.isSending && screen.pendingDisclosure == null,
                    intents.onSelectModel,
                )
            },
            contextUsage = screen.contextUsage,
            onCompact = intents.onCompact,
            canCompact =
                !screen.isDraft && !screen.isSending &&
                    screen.pendingDisclosure == null && screen.pendingAttachments.isEmpty(),
            reasoning = runControl.reasoning,
            reasoningSupported = screen.badge?.reasoningSupported == true,
            onReasoning = intents.onSetReasoning,
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
