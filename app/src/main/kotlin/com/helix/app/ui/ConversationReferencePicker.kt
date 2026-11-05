package com.helix.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.SessionRowUi
import kotlinx.coroutines.CancellationException

/** Directory facts are observed directly, independently of the conversation's message projection. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun ConversationReferencePicker(
    chatService: ChatService,
    currentSessionId: String,
    onSelect: (SessionRowUi) -> Unit,
    onDismiss: () -> Unit,
    selectedSessionId: String? = null,
) {
    val sessions by chatService.sessions.collectAsStateWithLifecycle()
    var query by rememberSaveable(currentSessionId) { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loaded by remember(chatService, currentSessionId) { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(chatService, currentSessionId, refresh) {
        loading = true
        try {
            chatService.refreshReferenceSessions().await()
            loaded = true
            failed = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    val candidates = sessions.filter { it.id != currentSessionId }
    val matches = candidates.filter { it.title.contains(query.trim(), ignoreCase = true) }
    if (!loaded && !failed) return
    ConversationSheet(
        stringResource(R.string.conversation_reference_title),
        "conversation-reference",
        onDismiss,
        avoidIme = true,
        scrollContent = false,
    ) {
        Text(stringResource(R.string.conversation_reference_hint))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.reference_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("reference-search"),
        )
        when {
            failed -> {
                Text(stringResource(R.string.reference_load_failed))
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.chat_retry)) }
            }

            candidates.isEmpty() -> {
                Text(stringResource(R.string.conversation_reference_empty))
            }

            matches.isEmpty() -> {
                Text(stringResource(R.string.reference_no_matches))
            }

            else -> {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(matches, key = { it.id }) { row ->
                        val count = row.referenceMessageCount
                        val metadata =
                            buildList {
                                if (count != null) add(stringResource(R.string.reference_message_count, count))
                                add(
                                    java.text.DateFormat
                                        .getDateTimeInstance(
                                            java.text.DateFormat.SHORT,
                                            java.text.DateFormat.SHORT,
                                        ).format(java.util.Date(row.lastActivityAt)),
                                )
                                if (row.isArchived) add(stringResource(R.string.reference_archived))
                                if (count == 0) add(stringResource(R.string.reference_no_text))
                            }.joinToString(" · ")
                        ConversationMenuRow(
                            title = row.title.ifBlank { stringResource(R.string.reference_untitled) },
                            onClick = { onSelect(row) },
                            enabled = !loading && count != 0,
                            modifier = Modifier.testTag("conversation-reference-${row.id}"),
                            supporting = metadata,
                            selected = selectedSessionId == row.id,
                        )
                    }
                }
            }
        }
    }
}
