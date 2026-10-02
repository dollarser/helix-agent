package com.helix.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.agent.ChatContextUsage
import com.helix.app.ui.composer.completeComposerText
import com.helix.app.ui.composer.synchronizeComposerText
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import kotlinx.coroutines.launch

@Composable
// Permission persistence failures remain visible; cancellation and uncertain commits are not reported as success.
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
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
    modelSelector: (@Composable () -> Unit)? = null,
    permissionMode: SessionPermissionMode? = null,
    onPermission: () -> Unit = {},
    onPermissionMode: suspend (SessionPermissionMode) -> Boolean = { false },
    onChooseModel: () -> Unit = {},
    editorKey: String? = null,
    contextUsage: ChatContextUsage = ChatContextUsage(),
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    headerStatus: @Composable () -> Unit = {},
    optionsContent: @Composable () -> Unit = {},
    turnState: TurnState? = null,
    availability: ComposerAvailability = ComposerAvailability(),
) {
    val commandScope = androidx.compose.runtime.rememberCoroutineScope()
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val compactInput =
        WindowInsets.ime.getBottom(density) > 0 || windowHeight < with(density) { 480.dp.roundToPx() }
    val currentEditorKey by rememberUpdatedState(editorKey)
    var commandPending by remember(editorKey) { mutableStateOf(false) }
    var permissionPending by remember(editorKey) { mutableStateOf(false) }
    var permissionNotice by remember(editorKey) { mutableStateOf<Int?>(null) }
    var editorValue by rememberSaveable(editorKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(input, TextRange(input.length)))
    }
    val fieldValue = synchronizeComposerText(editorValue, input)
    SideEffect { if (editorValue != fieldValue) editorValue = fieldValue }
    val inputFocus = remember { FocusRequester() }
    var addOpen by remember { mutableStateOf(false) }
    var commandNotice by remember(editorKey, input) { mutableStateOf<Int?>(null) }
    val command =
        com.helix.app.ui.composer.ComposerCommandParser
            .leadingCommand(input)
    val localOnly = command != null && input.trim() == "/${command.command}"
    val canDeliver = availability.canDeliver(localOnly)
    val commandColor = MaterialTheme.colorScheme.primary
    val commandBackground = MaterialTheme.colorScheme.primaryContainer
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("chat-composer")) {
        val activeQuery =
            remember(fieldValue.text, fieldValue.selection) {
                if (fieldValue.selection.collapsed) {
                    com.helix.app.ui.composer.ComposerCommandParser
                        .parseQuery(fieldValue.text, fieldValue.selection.end)
                } else {
                    null
                }
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
                    editorValue = completeComposerText(fieldValue, activeQuery, item)
                    onInput(editorValue.text)
                    inputFocus.requestFocus()
                },
            )
        }
        ComposerToolbar(mode, headerStatus, optionsContent) {
            ContextWindowIndicator(contextUsage, onCompact, canCompact)
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
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { value ->
                        editorValue = value
                        if (value.text != input) onInput(value.text)
                    },
                    modifier = Modifier.weight(1f).focusRequester(inputFocus).testTag("chat-input"),
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
                    maxLines = if (compactInput) 3 else 5,
                )

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
                        if (!canDeliver || commandPending || permissionPending) return@IconButton
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
                                            val switched = onMode(requestedMode)
                                            if (currentEditorKey != editorKey) return@launch
                                            if (switched) {
                                                val task = input.drop(command.command.length + 1).trimStart()
                                                onInput(task)
                                                if (task.isNotBlank()) actions.onSend()
                                            } else {
                                                commandNotice = R.string.chat_command_wait
                                            }
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            commandNotice = R.string.composer_mode_failed
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
                    enabled = sendEnabled && canDeliver && !commandPending && !permissionPending,
                    modifier = Modifier.testTag("chat-send"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_composer_send),
                        stringResource(R.string.common_send),
                    )
                }
            }
            val hasUnsentContent = input.isNotBlank() || hasAttachments || referenceLabel != null
            if (!availability.modelSelected && !localOnly && hasUnsentContent) {
                TextButton(onClick = onChooseModel, modifier = Modifier.testTag("chat-select-model-reminder")) {
                    Text(stringResource(R.string.chat_model_required_before_send))
                }
            }
            val unavailable = availability.unavailableReason(localOnly, commandPending, permissionPending)
            if (hasUnsentContent && unavailable != null && unavailable != R.string.chat_model_required_before_send) {
                Text(
                    stringResource(unavailable),
                    Modifier.padding(horizontal = 16.dp).testTag("chat-send-status"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            permissionNotice?.let {
                Text(
                    stringResource(it),
                    Modifier.padding(horizontal = 16.dp).testTag("chat-permission-notice"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("chat-composer-footer"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    ComposerPermissionMenu(permissionMode, permissionPending, onSelect = { selected ->
                        if (!permissionPending) {
                            permissionPending = true
                            permissionNotice = null
                            commandScope.launch {
                                try {
                                    if (!onPermissionMode(selected)) {
                                        permissionNotice =
                                            R.string.composer_permission_failed
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    permissionNotice = R.string.composer_permission_failed
                                } finally {
                                    permissionPending = false
                                }
                            }
                        }
                    }, onSettings = onPermission)
                }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    modelSelector?.invoke()
                }
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
