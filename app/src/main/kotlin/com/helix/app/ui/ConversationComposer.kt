package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun ConversationComposer(
    input: String,
    onInput: (String) -> Unit,
    isSending: Boolean,
    hasAttachments: Boolean,
    actions: ComposerActions,
    goalMode: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(8.dp).testTag("chat-composer")) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth().testTag("chat-input"),
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            enabled = !isSending,
            maxLines = 5,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(actions.onAttach, enabled = !isSending, modifier = Modifier.testTag("chat-attach")) {
                Text(stringResource(R.string.chat_attachment_button))
            }
            TextButton(actions.onVoice, enabled = !isSending, modifier = Modifier.testTag("chat-voice")) {
                Text(stringResource(R.string.chat_voice_button))
            }
            if (isSending) {
                Button(actions.onStop, modifier = Modifier.testTag("chat-stop")) {
                    Text(stringResource(R.string.chat_stop))
                }
            } else {
                Button(
                    actions.onSend,
                    enabled = goalMode || input.isNotBlank() || hasAttachments,
                    modifier = Modifier.testTag("chat-send"),
                ) { Text(stringResource(if (goalMode) R.string.chat_open_goals else R.string.common_send)) }
            }
        }
    }
}
