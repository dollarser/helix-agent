package com.helix.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.agent.ChatContextUsage
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun ConversationComposer(
    input: String,
    onInput: (String) -> Unit,
    isSending: Boolean,
    hasAttachments: Boolean,
    actions: ComposerActions,
    referenceLabel: String? = null,
    onRemoveReference: () -> Unit = {},
    goalMode: Boolean = false,
    mode: AgentMode = if (goalMode) AgentMode.GOAL else AgentMode.ACT,
    onMode: suspend (AgentMode) -> Boolean = { false },
    reasoning: ReasoningEffort = ReasoningEffort.OFF,
    reasoningSupported: Boolean = false,
    onReasoning: (ReasoningEffort) -> Unit = {},
    modelSelector: (@Composable () -> Unit)? = null,
    permissionMode: SessionPermissionMode? = null,
    onPermission: () -> Unit = {},
    contextUsage: ChatContextUsage = ChatContextUsage(),
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    reasoningOptions: List<ReasoningEffort> = ReasoningEffort.FALLBACK,
    turnState: TurnState? = null,
    availability: ComposerAvailability = ComposerAvailability(),
) {
    val commandScope = androidx.compose.runtime.rememberCoroutineScope()
    var commandPending by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var commandNotice by remember(input) { mutableStateOf<Int?>(null) }
    val command =
        com.helix.app.ui.composer.ComposerCommandParser
            .leadingCommand(input)
    val localOnly = command != null && input.trim() == "/${command.command}"
    val canDeliver = if (localOnly) availability.localCommands else availability.delivery
    val commandColor = MaterialTheme.colorScheme.primary
    val commandBackground = MaterialTheme.colorScheme.primaryContainer
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("chat-composer")) {
        val activeQuery =
            androidx.compose.runtime.remember(input) {
                com.helix.app.ui.composer.ComposerCommandParser
                    .parseQuery(input)
            }
        if (activeQuery != null) {
            val suggestions =
                androidx.compose.runtime.remember(activeQuery) {
                    com.helix.app.ui.composer.ComposerCommandParser
                        .filterSuggestions(activeQuery)
                }
            com.helix.app.ui.composer.ComposerAutocompletePopup(
                suggestions = suggestions,
                onSelect = { item ->
                    val (newText, _) =
                        com.helix.app.ui.composer.ComposerCommandParser
                            .applySuggestion(input, activeQuery, item)
                    onInput(newText)
                },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    actions.onVoice,
                    enabled = availability.input && !commandPending,
                    modifier = Modifier.testTag("chat-voice"),
                ) {
                    Icon(painterResource(R.drawable.ic_composer_voice), stringResource(R.string.chat_voice_button))
                }
                androidx.compose.foundation.layout
                    .Spacer(Modifier.weight(1f))
                IconButton(
                    { addOpen = true },
                    enabled = availability.canAttach() || availability.input,
                    modifier = Modifier.testTag("chat-add"),
                ) {
                    Icon(painterResource(R.drawable.ic_composer_add), stringResource(R.string.composer_add_title))
                }
                if (isSending) {
                    IconButton(
                        onClick = actions.onStop,
                        enabled = turnState != TurnState.CANCELLING,
                        modifier = Modifier.testTag("chat-stop"),
                    ) {
                        Icon(painterResource(R.drawable.ic_composer_stop), stringResource(R.string.chat_stop))
                    }
                }
                val sendEnabled =
                    input.isNotBlank() ||
                        ((!goalMode || isSending) && (hasAttachments || referenceLabel != null))
                IconButton(
                    onClick = {
                        when {
                            command == null -> {
                                actions.onSend()
                            }

                            input.trim() != "/${command.command}" &&
                                command.command !in setOf("chat", "plan", "act", "goal") -> {
                                commandNotice = R.string.chat_command_standalone
                            }

                            isSending && command.command !in setOf("help", "clear") -> {
                                commandNotice = R.string.chat_command_wait
                            }

                            command.command == "compact" && !canCompact -> {
                                commandNotice =
                                    R.string.chat_command_compact_unavailable
                            }

                            else -> {
                                val requestedMode =
                                    when (command.command) {
                                        "chat" -> AgentMode.CHAT
                                        "plan" -> AgentMode.PLAN
                                        "act" -> AgentMode.ACT
                                        "goal" -> AgentMode.GOAL
                                        else -> null
                                    }
                                if (requestedMode != null) {
                                    commandPending = true
                                    commandScope.launch {
                                        try {
                                            if (onMode(requestedMode)) {
                                                val task = input.drop(command.command.length + 1).trimStart()
                                                onInput(task)
                                                if (task.isNotBlank()) actions.onSend()
                                            } else {
                                                commandNotice = R.string.chat_command_wait
                                            }
                                        } finally {
                                            commandPending = false
                                        }
                                    }
                                } else {
                                    when (command.command) {
                                        "compact" -> {
                                            onCompact()
                                            onInput("")
                                        }

                                        "clear" -> {
                                            onInput("")
                                        }

                                        "help" -> {
                                            commandNotice = R.string.chat_command_help
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = sendEnabled && canDeliver && !commandPending,
                    modifier = Modifier.testTag("chat-send"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_composer_send),
                        stringResource(R.string.common_send),
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth().testTag("chat-input"),
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                enabled = availability.input && !commandPending,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                visualTransformation = { text ->
                    val styled =
                        androidx.compose.ui.text.AnnotatedString
                            .Builder(text)
                    if (command != null) {
                        styled.addStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = commandColor,
                                background = commandBackground,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            ),
                            0,
                            command.command.length + 1,
                        )
                    }
                    androidx.compose.ui.text.input.TransformedText(
                        styled.toAnnotatedString(),
                        androidx.compose.ui.text.input.OffsetMapping.Identity,
                    )
                },
                maxLines = 5,
            )
            commandNotice?.let {
                Text(
                    stringResource(it),
                    Modifier.padding(horizontal = 16.dp).testTag("chat-command-notice"),
                )
            }
            if (referenceLabel != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.composer_reference_chip, referenceLabel),
                        modifier = Modifier.weight(1f).testTag("composer-reference-chip"),
                    )
                    TextButton(
                        onClick = onRemoveReference,
                        enabled = !isSending,
                        modifier = Modifier.testTag("composer-reference-remove"),
                    ) {
                        Text(stringResource(R.string.common_remove))
                    }
                }
            }
            Text(
                stringResource(R.string.chat_current_mode, mode.name),
                Modifier.padding(horizontal = 16.dp).testTag("chat-current-mode"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                ComposerToolbar(
                    reasoning,
                    reasoningSupported,
                    onReasoning,
                    isSending,
                    modelSelector,
                    permissionMode = permissionMode,
                    onPermission = onPermission,
                    reasoningOptions = reasoningOptions,
                    trailingOptions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.chat_context_title), Modifier.weight(1f))
                            ContextWindowIndicator(contextUsage, onCompact, canCompact)
                        }
                    },
                )
            }
        }
    }
    if (addOpen) {
        ComposerAddSheet(
            messageEnabled = availability.canAttach(),
            sessionConfigEnabled = !isSending,
            actions = actions,
            onDismiss = { addOpen = false },
        )
    }
}
