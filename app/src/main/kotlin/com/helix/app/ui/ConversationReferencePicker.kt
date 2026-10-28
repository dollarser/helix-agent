package com.helix.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
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
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(chatService, currentSessionId, refresh) {
        loading = true
        failed = false
        try {
            chatService.refreshReferenceSessions().await()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    val candidates = sessions.filter { it.id != currentSessionId }
    val matches = candidates.filter { it.title.contains(query, ignoreCase = true) }
    ConversationSheet(
        stringResource(R.string.conversation_reference_title),
        "conversation-reference",
        onDismiss,
        avoidIme = true,
    ) {
        Text(stringResource(R.string.conversation_reference_hint))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.reference_search)) },
            modifier = Modifier.fillMaxWidth().testTag("reference-search"),
        )
        when {
            loading -> {
                Text(stringResource(R.string.tasks_commands_loading))
            }

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
                matches.forEach { row ->
                    ConversationMenuRow(
                        title = row.title,
                        onClick = { onSelect(row) },
                        modifier = Modifier.testTag("conversation-reference-${row.id}"),
                        supporting = if (row.isArchived) stringResource(R.string.reference_archived) else null,
                        selected = selectedSessionId == row.id,
                    )
                }
            }
        }
    }
}
