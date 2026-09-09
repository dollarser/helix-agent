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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.helix.app.chat.TurnUi

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
internal fun MessageRow(message: MessageUi) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color =
                if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            modifier =
                Modifier
                    .fillMaxWidth(
                        if (isUser) 0.88f else 1f,
                    ).testTag(if (isUser) "chat-message-user" else "chat-message-assistant"),
        ) {
            Column {
                val bodyModifier =
                    Modifier
                        .testTag("chat-message-body-${message.id}")
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp)
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
                CopyTextButton(message.content, "chat-copy-${message.id}")
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun TurnOperations(
    entry: ConversationEntry,
    active: TurnUi?,
    intents: ConversationIntents,
) {
    if (entry.tools.isEmpty() && entry.recoveries.isEmpty()) return
    var expanded by remember(entry.key) { mutableStateOf(false) }
    val needsAttention = entry.tools.any { it.card?.state == ApprovalCardState.PENDING || it.prootRecoveryAvailable }
    val live = active?.id == entry.key && !active.state.isTerminal
    val showOperations = expanded || live || needsAttention
    Column(Modifier.fillMaxWidth().testTag("turn-operations-${entry.key}")) {
        TextButton({ expanded = !expanded }, modifier = Modifier.testTag("turn-expand-${entry.key}")) {
            Text(stringResource(R.string.chat_operations_count, entry.tools.size))
        }
        if (showOperations || entry.recoveries.isNotEmpty()) {
            entry.tools.forEach { ToolTimelineItem(it, intents) }
            entry.recoveries.forEach {
                SubscriptionRecoveryActions(it, intents.onInspectSubscription, intents.onRecoverSubscriptionResult)
            }
        }
    }
}
