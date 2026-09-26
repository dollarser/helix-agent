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
import androidx.compose.runtime.Composable
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
import com.helix.core.model.TurnState

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun ConversationComposer(
    input: String,
    onInput: (String) -> Unit,
    isSending: Boolean,
    hasAttachments: Boolean,
    actions: ComposerActions,
    goalMode: Boolean = false,
    mode: AgentMode = if (goalMode) AgentMode.GOAL else AgentMode.CHAT,
    onMode: (AgentMode) -> Unit = {},
    reasoning: ReasoningEffort = ReasoningEffort.OFF,
    reasoningSupported: Boolean = false,
    onReasoning: (ReasoningEffort) -> Unit = {},
    modelSelector: (@Composable () -> Unit)? = null,
    contextUsage: ChatContextUsage = ChatContextUsage(),
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
    reasoningOptions: List<ReasoningEffort> = ReasoningEffort.FALLBACK,
    turnState: TurnState? = null,
    availability: ComposerAvailability = ComposerAvailability(),
) {
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
                    when (item.id) {
                        "slash:plan" -> {
                            onMode(AgentMode.CHAT)
                            onInput("")
                        }

                        "slash:act" -> {
                            onMode(AgentMode.CHAT)
                            onInput("")
                        }

                        "slash:goal" -> {
                            onMode(AgentMode.GOAL)
                            onInput("")
                        }

                        "slash:compact" -> {
                            if (canCompact) onCompact()
                            onInput("")
                        }

                        "slash:clear" -> {
                            onInput("")
                        }

                        else -> {
                            val (newText, _) =
                                com.helix.app.ui.composer.ComposerCommandParser.applySuggestion(
                                    input,
                                    activeQuery,
                                    item,
                                )
                            onInput(newText)
                        }
                    }
                },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth().testTag("chat-input"),
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                enabled = availability.input,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                maxLines = 5,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    actions.onVoice,
                    enabled = availability.input,
                    modifier = Modifier.testTag("chat-voice"),
                ) {
                    Icon(painterResource(R.drawable.ic_composer_voice), stringResource(R.string.chat_voice_button))
                }
                IconButton(
                    actions.onAttach,
                    enabled = availability.canAttach(),
                    modifier = Modifier.testTag("chat-attach"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chat_attach),
                        stringResource(R.string.chat_attachment_button),
                    )
                }
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    ComposerToolbar(
                        mode,
                        onMode,
                        reasoning,
                        reasoningSupported,
                        onReasoning,
                        isSending,
                        modelSelector,
                        reasoningOptions = reasoningOptions,
                        trailingOptions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.chat_context_title), Modifier.weight(1f))
                                ContextWindowIndicator(contextUsage, onCompact, canCompact)
                            }
                        },
                    )
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
                val sendEnabled = input.isNotBlank() || ((!goalMode || isSending) && hasAttachments)
                IconButton(
                    onClick = actions.onSend,
                    enabled = sendEnabled && availability.delivery,
                    modifier = Modifier.testTag("chat-send"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_composer_send),
                        stringResource(R.string.common_send),
                    )
                }
            }
        }
    }
}
