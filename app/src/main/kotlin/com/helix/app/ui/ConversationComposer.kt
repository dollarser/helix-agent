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
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
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
    contextUsage: com.helix.app.chat.ChatContextUsage =
        com.helix.app.chat
            .ChatContextUsage(),
    onCompact: () -> Unit = {},
    canCompact: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("chat-composer")) {
        ComposerToolbar(mode, onMode, reasoning, reasoningSupported, onReasoning, isSending, {
            Row(verticalAlignment = Alignment.CenterVertically) {
                modelSelector?.invoke()
                ContextWindowIndicator(contextUsage, onCompact, canCompact)
            }
        }) {
            if (input.isNotEmpty()) ComposerOptionPill { CopyTextButton(input, "chat-copy-input") }
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(actions.onVoice, enabled = !isSending, modifier = Modifier.testTag("chat-voice")) {
                Icon(painterResource(R.drawable.ic_composer_voice), stringResource(R.string.chat_voice_button))
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f).testTag("chat-input"),
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                enabled = !isSending,
                trailingIcon = {
                    IconButton(actions.onAttach, enabled = !isSending, modifier = Modifier.testTag("chat-attach")) {
                        Icon(
                            painterResource(R.drawable.ic_chat_attach),
                            stringResource(R.string.chat_attachment_button),
                        )
                    }
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                    ),
                maxLines = 5,
            )
            IconButton(
                onClick = if (isSending) actions.onStop else actions.onSend,
                enabled = isSending || goalMode || input.isNotBlank() || hasAttachments,
                modifier = Modifier.testTag(if (isSending) "chat-stop" else "chat-send"),
            ) {
                Icon(
                    painterResource(if (isSending) R.drawable.ic_composer_stop else R.drawable.ic_composer_send),
                    stringResource(
                        when {
                            isSending -> R.string.chat_stop
                            goalMode -> R.string.chat_open_goals
                            else -> R.string.common_send
                        },
                    ),
                )
            }
        }
    }
}
