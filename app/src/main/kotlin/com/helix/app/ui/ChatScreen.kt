package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageUi
import com.helix.app.chat.SessionRowUi
import com.helix.app.profile.SafetyProfile
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.TurnState
import kotlinx.coroutines.launch

/**
 * The chat UI (HXA-028). Two views over the service's observable state:
 * the session list (persisted sessions) and the open conversation
 * (persisted messages + the in-flight turn's [com.helix.app.chat.TurnUi]).
 *
 * The UI dispatches intents to [ChatService] and observes its StateFlows —
 * it NEVER holds a network Job (doc 02 section 12): the streaming Job lives
 * in the service. Error displays use the service's SAFE labels
 * ([com.helix.app.chat.TurnUi.errorLabel]) — never a raw exception message
 * (doc 02 section 13). The pre-send egress gate (ADR-0005 / doc 10 section
 * 2.6) surfaces as the [DisclosureDialog] when the service holds a pending
 * disclosure.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
fun ChatScreen(
    chatService: ChatService,
    providerService: ProviderService,
) {
    val screen by chatService.screen.collectAsStateWithLifecycle()
    val sessions by chatService.sessions.collectAsStateWithLifecycle()
    val profile by chatService.profile.collectAsStateWithLifecycle()
    val providerRows by providerService.rows.collectAsStateWithLifecycle()
    var newSessionOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .testTag("screen-sessions"),
    ) {
        if (screen.openSessionId == null) {
            SessionListSection(
                sessions = sessions,
                onNew = { newSessionOpen = true },
                onOpen = { chatService.openSession(it) },
                onArchive = { chatService.archiveSession(it) },
            )
        } else {
            ConversationSection(
                screen = screen,
                profile = profile,
                input = input,
                onInput = { input = it },
                intents =
                    ConversationIntents(
                        onBack = { chatService.closeSession() },
                        onSend = {
                            chatService.send(input.trim())
                            input = ""
                        },
                        onStop = { chatService.stop() },
                        onRetry = { chatService.retry() },
                        onDismissBlocked = { chatService.dismissBlocked() },
                    ),
            )
        }
    }

    if (newSessionOpen) {
        NewSessionDialog(
            providers = providerRows.filter { it.chatSelectable },
            onCreated = { title, row ->
                val id = chatService.createSession(title, row.id, row.model)
                newSessionOpen = false
                chatService.openSession(id)
            },
            onDismiss = { newSessionOpen = false },
        )
    }

    screen.pendingDisclosure?.let { summary ->
        DisclosureDialog(
            summary = summary,
            onConfirm = { chatService.confirmSend() },
            onDismiss = { chatService.cancelPendingSend() },
        )
    }
}

// The Compose DSL keeps each section in one composable; detekt's LongMethod
// does not model UI composition well, so it is suppressed per composable
// (same convention as the app shell).
@Composable
@Suppress("FunctionName", "LongMethod")
private fun SessionListSection(
    sessions: List<SessionRowUi>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onArchive: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("chat-session-list"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("会话", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = onNew,
                    modifier = Modifier.testTag("chat-new-session"),
                ) {
                    Text("新建会话")
                }
            }
        }
        if (sessions.isEmpty()) {
            item(key = "empty") {
                Text(
                    "暂无会话。新建会话前，请先在 设置 → Provider 中添加 Provider 并通过连接测试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(sessions, key = { it.id }) { session ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(
                    modifier =
                        Modifier
                            .clickable { onOpen(session.id) }
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(session.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            UiLabels.formatTime(session.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        buildString {
                            append(session.providerName ?: "无 Provider")
                            if (session.model != null) append(" · ${session.model}")
                            if (session.isArchived) append(" · 已归档")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(
                            onClick = { onArchive(session.id) },
                            enabled = !session.isArchived,
                            modifier = Modifier.testTag("chat-archive"),
                        ) {
                            Text("归档")
                        }
                    }
                }
            }
        }
    }
}

// The create race (provider became untested between render and click) is the
// ONLY expected exception on this path; its internal message is never shown
// raw (doc 02 section 13), so the catch is an intentional, documented no-op
// beyond setting the user-visible error state.
@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException")
private fun NewSessionDialog(
    providers: List<ProviderRowUi>,
    onCreated: suspend (String, ProviderRowUi) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val canCreate = title.isNotBlank() && selected != null && providers.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会话标题") },
                    singleLine = true,
                    modifier = Modifier.testTag("chat-new-session-title"),
                )
                if (providers.isEmpty()) {
                    Text(
                        "没有可用 Provider：请先在 设置 → Provider 中添加并完成连接测试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    providers.forEach { row ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = row.id }
                                    .testTag("chat-new-session-provider"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == row.id,
                                onClick = { selected = row.id },
                            )
                            Text(
                                "${row.displayName}（${row.model}）",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            // The create runs on the dialog's coroutine scope: the Room write
            // happens on the chat service's IO scope (createSession is
            // suspend), never on this UI thread.
            TextButton(
                enabled = canCreate,
                onClick = {
                    val row = providers.firstOrNull { it.id == selected }
                    if (row == null) return@TextButton
                    scope.launch {
                        try {
                            onCreated(title.trim(), row)
                        } catch (e: IllegalArgumentException) {
                            // The create `require(...)` fires only on a race
                            // (the provider became untested between render and
                            // click); the internal English message is never
                            // shown raw (doc 02 section 13).
                            error = "创建失败：该 Provider 当前不可用（请确认其连接测试状态）"
                        }
                    }
                },
                modifier = Modifier.testTag("chat-new-session-confirm"),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("chat-new-session-cancel")) {
                Text("取消")
            }
        },
        modifier = Modifier.testTag("chat-new-session-dialog"),
    )
}

/** The conversation's intents, bundled so the composable stays within the parameter budget. */
data class ConversationIntents(
    val onBack: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onRetry: () -> Unit,
    val onDismissBlocked: () -> Unit,
)

@Composable
@Suppress("FunctionName", "LongMethod")
private fun ConversationSection(
    screen: ChatScreenState,
    profile: SafetyProfile,
    input: String,
    onInput: (String) -> Unit,
    intents: ConversationIntents,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = intents.onBack, modifier = Modifier.testTag("chat-back")) {
                Text("会话列表")
            }
            screen.badge?.let { badge ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "${badge.displayName} · ${badge.model}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${UiLabels.displayOrigin(badge.origin)} · ${UiLabels.residenceLabel(badge.residence)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (badge.chips.isNotEmpty()) {
                        Text(
                            badge.chips.joinToString("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                if (profile == SafetyProfile.ADVANCED) "配置：Advanced" else "配置：Standard",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chat-profile"),
            )
        }
        screen.blockedReason?.let { reason ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f).testTag("chat-blocked-reason"),
                )
                TextButton(onClick = intents.onDismissBlocked) { Text("知道了") }
            }
        }
        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(screen.messages, key = { it.id }) { message ->
                MessageRow(message)
            }
            val turn = screen.activeTurn
            if (turn != null && !turn.state.isTerminal) {
                item(key = "streaming") {
                    if (turn.streamingText.isNullOrBlank()) {
                        Text(
                            "正在等待模型响应…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        MessageRow(MessageUi("streaming", "assistant", turn.streamingText.orEmpty()))
                    }
                }
            }
            if (turn != null && turn.state == TurnState.FAILED && turn.errorLabel != null) {
                item(key = "turn-error") {
                    Column {
                        Text(
                            "本次请求未成功：${turn.errorLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("chat-turn-error"),
                        )
                        if (screen.retryTargetTurnId != null) {
                            TextButton(onClick = intents.onRetry, modifier = Modifier.testTag("chat-retry")) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f).testTag("chat-input"),
                placeholder = { Text("输入消息…") },
                enabled = !screen.isSending,
            )
            if (screen.isSending) {
                Button(onClick = intents.onStop, modifier = Modifier.testTag("chat-stop")) {
                    Text("停止")
                }
            } else {
                Button(
                    onClick = intents.onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.testTag("chat-send"),
                ) {
                    Text("发送")
                }
            }
        }
    }
}

/** One message bubble; the alignment branch is a plain Row (no scope tricks). */
@Composable
@Suppress("FunctionName")
private fun MessageRow(message: MessageUi) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            modifier = Modifier.testTag(if (isUser) "chat-message-user" else "chat-message-assistant"),
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
