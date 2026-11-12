package com.helix.feature.conversation.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.dp
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.model.input.InputSkillActivation
import com.helix.feature.conversation.R
import com.helix.feature.conversation.presentation.ChatContextUsage
import com.helix.feature.conversation.presentation.composer.AutocompleteQuery
import com.helix.feature.conversation.presentation.composer.ComposerCommandParser
import com.helix.feature.conversation.presentation.composer.ComposerSkillTokens
import com.helix.feature.conversation.presentation.composer.ComposerSuggestionItem
import com.helix.feature.conversation.presentation.composer.ComposerSuggestionType
import com.helix.feature.conversation.presentation.composer.completeComposerText
import com.helix.feature.conversation.presentation.composer.synchronizeComposerText
import kotlinx.coroutines.launch
import com.helix.ui.shared.R as SharedR

@Composable
// Independent permission, mode and skill operations rethrow cancellation; never convert it into success.
// Permission persistence failures remain visible; cancellation and uncertain commits are not reported as success.
@Suppress(
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught",
    "ThrowsCount",
)
fun ConversationComposer(
    input: String,
    onInput: (String) -> Unit,
    isSending: Boolean,
    hasAttachments: Boolean,
    actions: ComposerActions,
    hasKnowledgeReferences: Boolean = false,
    referenceLabel: String? = null,
    pluginNames: List<String> = emptyList(),
    expertName: String? = null,
    onSelectedPlugins: () -> Unit = actions.onConnectors,
    onRemoveReference: () -> Unit = {},
    goalMode: Boolean = false,
    mode: AgentMode = if (goalMode) AgentMode.GOAL else AgentMode.ACT,
    onMode: suspend (AgentMode) -> Boolean = { false },
    modelSelector: (@Composable () -> Unit)? = null,
    permissionMode: SessionPermissionMode? = null,
    onPermission: () -> Unit = {},
    onPermissionMode: suspend (SessionPermissionMode) -> Boolean = { false },
    editorKey: String? = null,
    contextUsage: ChatContextUsage = ChatContextUsage(),
    onCompact: (String?) -> Unit = {},
    canCompact: Boolean = false,
    skillActivations: List<InputSkillActivation> = emptyList(),
    skillLabels: Map<String, String> = emptyMap(),
    skillSuggestions: List<ComposerSuggestionItem> = emptyList(),
    onSkillActivationsChange: (List<InputSkillActivation>) -> Unit = {},
    onSkillSelect: suspend (ComposerSuggestionItem) -> Boolean = { false },
    headerStatus: (@Composable () -> Unit)? = null,
    optionsContent: @Composable () -> Unit = {},
    attachmentsContent: @Composable () -> Unit = {},
    voiceActive: Boolean = false,
    voiceDraftEditable: Boolean = false,
    voiceContent: @Composable () -> Unit = {},
    referenceContent: @Composable () -> Unit = {},
    turnState: TurnState? = null,
    availability: ComposerAvailability = ComposerAvailability(),
) {
    val voiceBlocksEditing = voiceActive && !voiceDraftEditable
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
        mutableStateOf(
            TextFieldValue(
                ComposerSkillTokens.editorText(input, skillActivations.size),
                TextRange(input.length + skillActivations.size),
            ),
        )
    }
    val skillIdentities = skillActivations.map { "${it.source}:${it.name}:${it.snapshotHash}:${it.ownerId}" }
    val tokenLabels =
        skillActivations.map {
            skillLabels["${it.source}:${it.name}:${it.snapshotHash}:${it.ownerId}"]
                ?: it.name
        }
    var tokenPositions by rememberSaveable(editorKey) { mutableStateOf(skillActivations.indices.toList()) }
    var editorSkills by rememberSaveable(editorKey) { mutableStateOf(skillIdentities) }
    val synchronized =
        ComposerSkillTokens.plainText(editorValue.text, tokenPositions) == input && editorSkills == skillIdentities
    val fieldTokenPositions = if (synchronized) tokenPositions else skillActivations.indices.toList()
    val fieldValue =
        if (synchronized) {
            editorValue
        } else {
            synchronizeComposerText(
                editorValue,
                ComposerSkillTokens.editorText(input, skillActivations.size),
            )
        }
    SideEffect {
        if (!synchronized) {
            tokenPositions = fieldTokenPositions
            editorSkills = skillIdentities
        }
    }
    var textLayout by remember(editorKey) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var skillPending by remember(editorKey) { mutableStateOf(false) }
    val editorBlocked = commandPending || skillPending || voiceBlocksEditing
    val editorEnabled = availability.input && !editorBlocked
    val submissionPending = commandPending || skillPending || permissionPending
    SideEffect { if (editorValue != fieldValue) editorValue = fieldValue }
    val inputFocus = remember { FocusRequester() }
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    var editorFocused by remember(editorKey) { mutableStateOf(false) }
    var focusRestoreRequest by remember(editorKey) { mutableStateOf<Pair<String?, Boolean>?>(null) }
    val currentEditorEnabled by rememberUpdatedState(editorEnabled)
    LaunchedEffect(focusRestoreRequest, skillPending, editorKey) {
        val request = focusRestoreRequest ?: return@LaunchedEffect
        if (!skillPending) {
            // The disabled field must rejoin the focus tree before requesting its cursor/IME back.
            withFrameNanos { }
            if (currentEditorKey == request.first && currentEditorEnabled) {
                inputFocus.requestFocus()
                if (request.second) softwareKeyboard?.show()
            }
            focusRestoreRequest = null
        }
    }
    var addOpen by remember { mutableStateOf(false) }
    var commandNotice by remember(editorKey, input) { mutableStateOf<Int?>(null) }
    val command =
        if (skillActivations.isNotEmpty()) {
            null
        } else {
            ComposerCommandParser
                .leadingCommand(
                    if (com.helix.core.agent.ContextCommands
                            .parseCompact(input) != null
                    ) {
                        input.trimStart()
                    } else {
                        input
                    },
                )
        }
    val localOnly = command != null && input.trim() == "/${command.command}"
    val canAttempt = availability.canAttempt(localOnly)
    val context = LocalContext.current
    val commandColor = MaterialTheme.colorScheme.primary
    val chipBackground = MaterialTheme.colorScheme.secondaryContainer
    val chipOutline = MaterialTheme.colorScheme.outlineVariant
    val removeSkillLabel = stringResource(R.string.composer_remove_skill)
    var popupDismissed by remember(fieldValue.text, fieldValue.selection) { mutableStateOf(false) }
    var automaticInvocation by remember(editorKey) { mutableStateOf<String?>(null) }
    val leadingInvocation = ComposerSkillTokens.leadingInvocation(fieldValue.text, fieldTokenPositions)
    val leadingSkillCandidates =
        skillSuggestions.filter {
            it.type == ComposerSuggestionType.SKILL &&
                it.label.removePrefix("/").equals(leadingInvocation?.token, ignoreCase = true)
        }
    val activeQuery =
        remember(
            fieldValue.text,
            fieldValue.selection,
            fieldValue.composition,
            skillPending,
            popupDismissed,
            leadingInvocation,
            leadingSkillCandidates,
        ) {
            val queryVisible = !skillPending && !popupDismissed
            if (fieldValue.selection.collapsed && fieldValue.composition == null && queryVisible) {
                ComposerCommandParser.parseQuery(fieldValue.text, fieldValue.selection.end)
                    ?: leadingInvocation.takeIf {
                        leadingSkillCandidates.isNotEmpty() && ComposerCommandParser.leadingCommand(input) == null
                    }
            } else {
                null
            }
        }
    val suggestions =
        remember(activeQuery, skillSuggestions, skillActivations) {
            activeQuery
                ?.let {
                    ComposerCommandParser.filterSuggestions(
                        it,
                        commands =
                            if (skillActivations.isEmpty()) {
                                ComposerCommandParser.BUILTIN_SLASH_COMMANDS
                            } else {
                                emptyList()
                            },
                        skills = skillSuggestions,
                    )
                }.orEmpty()
        }
    var highlightedSuggestion by remember(activeQuery, suggestions) { mutableStateOf(0) }

    fun selectSuggestion(
        item: ComposerSuggestionItem,
        selectionQuery: AutocompleteQuery? = activeQuery,
        selectedValue: TextFieldValue = fieldValue,
        selectedPositions: List<Int> = fieldTokenPositions,
    ) {
        val query = selectionQuery ?: return
        if (!availability.input || voiceActive || skillPending) return
        if (item.type == ComposerSuggestionType.SKILL) {
            if (!skillPending) {
                val restoreKeyboard = editorFocused
                skillPending = true
                val requestedEditor = editorKey
                commandScope.launch {
                    try {
                        val selected = onSkillSelect(item)
                        if (!selected && currentEditorKey == requestedEditor) {
                            commandNotice = R.string.composer_skill_unavailable
                        }
                        if (selected && currentEditorKey == requestedEditor) {
                            val completed =
                                completeComposerText(selectedValue, query, item.copy(insertText = ""))
                            val retained =
                                ComposerSkillTokens.retainedPositions(
                                    selectedValue.text,
                                    completed.text,
                                    selectedPositions,
                                )
                            editorValue = completed
                            tokenPositions = retained.map { it.second }
                            onInput(ComposerSkillTokens.plainText(completed.text, tokenPositions))
                        }
                        if (currentEditorKey == requestedEditor) {
                            focusRestoreRequest = requestedEditor to restoreKeyboard
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (currentEditorKey == requestedEditor) {
                            commandNotice = R.string.composer_skill_unavailable
                            focusRestoreRequest = requestedEditor to restoreKeyboard
                        }
                    } finally {
                        skillPending = false
                    }
                }
            }
        } else {
            editorValue = completeComposerText(selectedValue, query, item)
            tokenPositions =
                ComposerSkillTokens
                    .retainedPositions(
                        selectedValue.text,
                        editorValue.text,
                        selectedPositions,
                    ).map { it.second }
            onInput(ComposerSkillTokens.plainText(editorValue.text, tokenPositions))
        }
        if (item.type != ComposerSuggestionType.SKILL) inputFocus.requestFocus()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("chat-composer")) {
        if (activeQuery != null && !voiceActive) {
            com.helix.feature.conversation.presentation.composer.ComposerAutocompletePopup(
                suggestions = suggestions,
                onSelect = { selectSuggestion(it) },
                highlightedIndex = highlightedSuggestion,
            )
        }
        ComposerHeader(
            mode = mode,
            pluginNames = pluginNames,
            onPlugins = onSelectedPlugins,
            permissionContent = {
                ComposerPermissionMenu(permissionMode, permissionPending, onSelect = { selected ->
                    if (!permissionPending) {
                        permissionPending = true
                        permissionNotice = null
                        commandScope.launch {
                            try {
                                if (!onPermissionMode(selected)) {
                                    permissionNotice = R.string.composer_permission_failed
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
            },
            headerStatus = headerStatus,
            optionsContent = optionsContent,
            contextIndicator = {
                ContextWindowIndicator(contextUsage)
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
        ) {
            referenceContent()
            voiceContent()
            attachmentsContent()
            BasicTextField(
                value = fieldValue,
                onValueChange = { value ->
                    val retained =
                        ComposerSkillTokens.retainedPositions(
                            fieldValue.text,
                            value.text,
                            fieldTokenPositions,
                        )
                    val indices = retained.map { it.first }
                    editorValue = value
                    tokenPositions = retained.map { it.second }
                    editorSkills = indices.map(skillIdentities::get)
                    if (indices != skillActivations.indices.toList()) {
                        onSkillActivationsChange(indices.map(skillActivations::get))
                    }
                    val plain = ComposerSkillTokens.plainText(value.text, tokenPositions)
                    if (plain != input) onInput(plain)
                    val invocation = ComposerSkillTokens.committedInvocation(fieldValue, value, tokenPositions)
                    if (ComposerSkillTokens.leadingInvocation(value.text, tokenPositions) == null) {
                        automaticInvocation = null
                    }
                    if (invocation != null && automaticInvocation != invocation.token) {
                        val candidates =
                            skillSuggestions.filter {
                                it.type == ComposerSuggestionType.SKILL &&
                                    it.label.removePrefix("/").equals(invocation.token, ignoreCase = true)
                            }
                        if (candidates.size == 1) {
                            automaticInvocation = invocation.token
                            selectSuggestion(candidates.single(), invocation, value, tokenPositions)
                        }
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = 24.dp)
                        .focusRequester(inputFocus)
                        .onFocusChanged { editorFocused = it.isFocused }
                        .testTag("chat-input")
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || suggestions.isEmpty() || skillPending) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        highlightedSuggestion =
                                            (highlightedSuggestion + 1) % suggestions.size
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        highlightedSuggestion =
                                            (highlightedSuggestion + suggestions.size - 1) % suggestions.size
                                        true
                                    }

                                    Key.Enter, Key.Tab -> {
                                        selectSuggestion(suggestions[highlightedSuggestion])
                                        true
                                    }

                                    Key.Escape -> {
                                        popupDismissed = true
                                        true
                                    }

                                    else -> {
                                        false
                                    }
                                }
                            }
                        }.semantics {
                            customActions =
                                skillActivations.mapIndexed { index, activation ->
                                    CustomAccessibilityAction("$removeSkillLabel ${tokenLabels[index]}") {
                                        if (editorEnabled) {
                                            onSkillActivationsChange(skillActivations - activation)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                }
                        }.drawBehind {
                            val layout = textLayout ?: return@drawBehind
                            layout.layoutInput.text
                                .getStringAnnotations("skill", 0, layout.layoutInput.text.length)
                                .forEach { annotation ->
                                    val firstLine = layout.getLineForOffset(annotation.start)
                                    val lastLine = layout.getLineForOffset(annotation.end - 1)
                                    for (line in firstLine..lastLine) {
                                        val start = maxOf(annotation.start, layout.getLineStart(line))
                                        val end = minOf(annotation.end, layout.getLineEnd(line))
                                        if (start < end) {
                                            val bounds = layout.getPathForRange(start, end).getBounds()
                                            drawRoundRect(
                                                chipBackground,
                                                bounds.topLeft,
                                                bounds.size,
                                                CornerRadius(6.dp.toPx()),
                                            )
                                            drawRoundRect(
                                                chipOutline,
                                                bounds.topLeft,
                                                bounds.size,
                                                CornerRadius(6.dp.toPx()),
                                                style = Stroke(1.dp.toPx()),
                                            )
                                        }
                                    }
                                }
                        },
                enabled = editorEnabled,
                onTextLayout = { textLayout = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (fieldValue.text.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_input_placeholder),
                                modifier = Modifier.testTag("chat-input-placeholder"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
                visualTransformation = { text ->
                    val transformed =
                        ComposerSkillTokens.transform(
                            text.text,
                            tokenLabels,
                            commandColor,
                            fieldTokenPositions,
                        )
                    if (command == null) {
                        transformed
                    } else {
                        val start = text.text.indexOf("/${command.command}")
                        val styled =
                            androidx.compose.ui.text.AnnotatedString
                                .Builder(transformed.text)
                        if (start >= 0) {
                            styled.addStyle(
                                SpanStyle(color = commandColor, fontWeight = FontWeight.SemiBold),
                                transformed.offsetMapping.originalToTransformed(start),
                                transformed.offsetMapping.originalToTransformed(start + command.command.length + 1),
                            )
                        }
                        TransformedText(styled.toAnnotatedString(), transformed.offsetMapping)
                    }
                },
                maxLines = if (compactInput) 3 else 5,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    { addOpen = true },
                    enabled = !voiceActive && !skillPending && (availability.canAttach() || availability.input),
                    modifier = Modifier.testTag("chat-add"),
                ) {
                    Icon(painterResource(SharedR.drawable.ic_composer_add), stringResource(R.string.composer_add_title))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    modelSelector?.invoke()
                }
                IconButton(
                    actions.onVoice,
                    enabled = availability.input && !commandPending && !skillPending && !voiceActive,
                    modifier = Modifier.testTag("chat-voice"),
                ) {
                    Icon(
                        painterResource(SharedR.drawable.ic_composer_voice),
                        stringResource(R.string.chat_voice_button),
                    )
                }

                if (isSending) {
                    IconButton(
                        onClick = actions.onStop,
                        enabled = turnState != TurnState.CANCELLING,
                        modifier = Modifier.testTag("chat-stop"),
                    ) {
                        Icon(painterResource(SharedR.drawable.ic_composer_stop), stringResource(R.string.chat_stop))
                    }
                }
                val sendEnabled =
                    input.isNotBlank() || skillActivations.isNotEmpty() ||
                        (
                            (!goalMode || isSending) &&
                                (hasAttachments || hasKnowledgeReferences || referenceLabel != null)
                        )
                IconButton(
                    onClick = {
                        if (!canAttempt || submissionPending) return@IconButton
                        val exactSkills =
                            if (ComposerCommandParser.leadingCommand(input) == null) {
                                suggestions.filter {
                                    it.type == ComposerSuggestionType.SKILL &&
                                        it.label.removePrefix("/").equals(activeQuery?.token, ignoreCase = true)
                                }
                            } else {
                                emptyList()
                            }
                        if (exactSkills.isNotEmpty()) {
                            if (fieldValue.composition != null) return@IconButton
                            if (exactSkills.size == 1) {
                                selectSuggestion(exactSkills.single())
                            } else {
                                commandNotice = R.string.composer_skill_choose
                            }
                            return@IconButton
                        }
                        if (!localOnly && !availability.modelSelected) {
                            android.widget.Toast
                                .makeText(
                                    context,
                                    R.string.chat_model_required_before_send,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            return@IconButton
                        }
                        when {
                            command == null -> {
                                actions.onSend()
                            }

                            input.trim() != "/${command.command}" &&
                                command.command !in setOf("chat", "plan", "act", "goal", "compact") -> {
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
                                            onCompact(
                                                com.helix.core.agent.ContextCommands
                                                    .parseCompact(input)
                                                    ?.focus,
                                            )
                                            onInput("")
                                        }

                                        "clear" -> {
                                            onSkillActivationsChange(emptyList())
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
                    enabled =
                        sendEnabled && canAttempt && !commandPending && !skillPending && !permissionPending &&
                            !voiceActive,
                    modifier = Modifier.testTag("chat-send"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_composer_send),
                        stringResource(SharedR.string.common_send),
                    )
                }
            }
            val hasUnsentContent =
                input.isNotBlank() || skillActivations.isNotEmpty() || hasAttachments || hasKnowledgeReferences ||
                    referenceLabel != null
            val unavailable = availability.unavailableReason(localOnly, commandPending, permissionPending)
            if (hasUnsentContent && unavailable != null) {
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
                        Text(stringResource(SharedR.string.common_remove))
                    }
                }
            }
        }
    }
    if (addOpen) {
        ComposerAddSheet(
            messageEnabled = availability.canAttach(),
            capabilityEnabled = !isSending,
            actions = actions,
            onDismiss = { addOpen = false },
            expertName = expertName,
            pluginNames = pluginNames,
        )
    }
}
