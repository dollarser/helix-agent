package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Keep the conversation usable when persistent details would consume a small viewport. */
@Composable
@Suppress("FunctionName")
internal fun AdaptiveConversationHeader(
    summary: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val height =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    val compact = height <= 640.dp || configuration.fontScale >= 1.3f
    var details by remember { mutableStateOf(false) }
    if (!compact) {
        content()
    } else {
        Column(Modifier.fillMaxWidth()) {
            Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow {
                TextButton(onBack, modifier = Modifier.testTag("chat-back")) {
                    Text(stringResource(R.string.chat_back_to_sessions))
                }
                TextButton({ details = true }, modifier = Modifier.testTag("chat-conversation-details")) {
                    Text(stringResource(R.string.chat_conversation_details))
                }
            }
        }
        if (details) {
            AlertDialog(
                onDismissRequest = { details = false },
                title = { Text(stringResource(R.string.chat_conversation_details)) },
                text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { content() } },
                confirmButton = {
                    TextButton({ details = false }, modifier = Modifier.testTag("chat-conversation-details-close")) {
                        Text(stringResource(R.string.chat_details_close))
                    }
                },
            )
        }
    }
}
