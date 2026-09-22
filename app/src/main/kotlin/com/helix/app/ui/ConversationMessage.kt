package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.chat.ConversationEntry
import com.helix.app.chat.MessageUi

@Composable
@Suppress("FunctionName")
internal fun CopyTextButton(
    text: String,
    tag: String,
) {
    val clipboard = LocalClipboardManager.current
    IconButton(
        onClick = { clipboard.setText(AnnotatedString(text)) },
        enabled = text.isNotEmpty(),
        modifier = Modifier.size(48.dp).testTag(tag),
    ) {
        Icon(
            painterResource(R.drawable.ic_chat_copy),
            stringResource(R.string.chat_copy_all),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun MessageRow(
    message: MessageUi,
    onFork: ((String) -> Unit)? = null,
) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 1f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color =
                    if (isUser) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(if (isUser) "chat-message-user" else "chat-message-assistant"),
            ) {
                Column {
                    val bodyModifier =
                        Modifier
                            .testTag("chat-message-body-${message.id}")
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    SelectionContainer {
                        if (!isUser) {
                            MarkdownText(message.content, bodyModifier)
                        } else {
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = bodyModifier,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CopyTextButton(message.content, "chat-copy-${message.id}")
                if (onFork != null) {
                    TextButton(
                        onClick = { onFork(message.id) },
                        modifier = Modifier.testTag("chat-fork-${message.id}"),
                    ) {
                        Text(stringResource(R.string.session_fork_action))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun TurnOperations(
    entry: ConversationEntry,
    intents: ConversationIntents,
) {
    if (entry.tools.isEmpty() && entry.recoveries.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("turn-operations-${entry.key}")) {
        entry.tools.forEach { ToolTimelineItem(it, intents) }
        if (entry.recoveries.isNotEmpty()) {
            entry.recoveries.forEach {
                SubscriptionRecoveryActions(it, intents.onInspectSubscription, intents.onRecoverSubscriptionResult)
            }
        }
    }
}
