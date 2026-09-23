package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.chat.ConversationEntry
import com.helix.app.chat.MessageUi

private const val MESSAGE_COPY_FEEDBACK_MS = 1500L

@Composable
@Suppress("FunctionName")
internal fun CopyTextButton(
    text: String,
    tag: String,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(MESSAGE_COPY_FEEDBACK_MS)
            copied = false
        }
    }

    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        enabled = text.isNotEmpty(),
        modifier = Modifier.size(48.dp).testTag(tag),
    ) {
        val iconRes = if (copied) R.drawable.ic_check else R.drawable.ic_chat_copy
        val descRes = if (copied) R.string.code_copied else R.string.chat_copy_all
        val tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            painterResource(iconRes),
            stringResource(descRes),
            tint = tint,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun ShareTextButton(
    text: String,
    tag: String,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(
        onClick = {
            val sendIntent =
                android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                }
            val shareIntent = android.content.Intent.createChooser(sendIntent, null)
            context.startActivity(shareIntent)
        },
        enabled = text.isNotEmpty(),
        modifier = Modifier.size(48.dp).testTag(tag),
    ) {
        Icon(
            painterResource(R.drawable.ic_chat_share),
            stringResource(R.string.chat_share_message),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun MessageRow(
    message: MessageUi,
    onFork: ((String) -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    searchQuery: String? = null,
    isCurrentMatch: Boolean = false,
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
                border =
                    if (isCurrentMatch) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
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
                            val parsed = ThinkingParser.parse(message.content)
                            if (parsed.thinking != null) {
                                ThinkingAccordion(
                                    thinking = parsed.thinking,
                                    isStreaming = parsed.isStreaming,
                                    messageId = message.id,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                            MarkdownText(parsed.text, bodyModifier, searchQuery)
                        } else {
                            val highlightStyle =
                                SpanStyle(
                                    background = MaterialTheme.colorScheme.primaryContainer,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                            val annotated =
                                if (!searchQuery.isNullOrBlank()) {
                                    highlightAnnotatedString(
                                        AnnotatedString(message.content),
                                        searchQuery,
                                        highlightStyle,
                                    )
                                } else {
                                    AnnotatedString(message.content)
                                }
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = bodyModifier,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CopyTextButton(message.content, "chat-copy-${message.id}")
                ShareTextButton(message.content, "chat-share-${message.id}")
                if (isUser && onEdit != null) {
                    TextButton(
                        onClick = { onEdit(message.id) },
                        modifier = Modifier.testTag("chat-edit-${message.id}"),
                    ) {
                        Text(stringResource(R.string.message_revision_action))
                    }
                }
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
    activeCallId: String? = null,
) {
    if (entry.tools.isEmpty() && entry.recoveries.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("turn-operations-${entry.key}")) {
        entry.tools.forEach {
            ToolTimelineItem(
                row = it,
                intents = intents,
                isCurrentMatch = it.callId == activeCallId,
            )
        }
        if (entry.recoveries.isNotEmpty()) {
            entry.recoveries.forEach {
                SubscriptionRecoveryActions(it, intents.onInspectSubscription, intents.onRecoverSubscriptionResult)
            }
        }
    }
}
