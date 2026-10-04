package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService
import com.helix.app.ui.IndicatedLazyColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only session outputs. Closing or switching sessions discards only presentation state. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ConversationArtifacts(
    service: ChatService,
    fileManager: FileManagerService?,
    screen: ChatScreenState,
) {
    if (fileManager == null || screen.isDraft) return
    val sessionId = screen.openSessionId ?: return
    var rows by remember(sessionId) { mutableStateOf(emptyList<ArtifactRowUi>()) }
    var open by remember(sessionId) { mutableStateOf(false) }
    var selectedId by remember(sessionId) { mutableStateOf<String?>(null) }
    var revision by remember(sessionId) { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    // Refresh when a tool result settles, a turn finishes, the app resumes or the user opens
    // the panel. Streamed assistant tokens do not cause reads. Session changes cancel old reads.
    LaunchedEffect(sessionId, screen.toolTimeline, screen.isSending, open, revision) {
        rows = service.conversationArtifacts(sessionId)
    }
    run {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().testTag("chat-artifacts-open"),
        ) {
            Text(stringResource(R.string.chat_artifacts_count, rows.size))
        }
    }
    if (open) {
        val listState = rememberLazyListState()
        val selected = rows.firstOrNull { it.id == selectedId }
        ConversationSheet(
            title = selected?.fileName ?: stringResource(R.string.chat_artifacts_title),
            tag = "chat-artifacts",
            onDismiss = {
                open = false
                selectedId = null
            },
            scrollContent = false,
        ) {
            if (selected == null) {
                if (rows.isEmpty()) Text(stringResource(R.string.tasks_artifacts_empty))
                IndicatedLazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("chat-artifacts-list"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        ArtifactFileRowView(row, onOpen = { selectedId = row.id })
                    }
                }
            } else {
                TextButton(
                    onClick = { selectedId = null },
                    modifier = Modifier.testTag("chat-artifacts-back"),
                ) { Text(stringResource(R.string.chat_artifacts_back)) }
                ConversationArtifactPreview(fileManager, selected, revision)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ConversationArtifactPreview(
    fileManager: FileManagerService,
    row: ArtifactRowUi,
    revision: Int,
) {
    var state by remember(row, revision) { mutableStateOf<ArtifactAvailability>(ArtifactAvailability.Loading) }
    var deliveryOpen by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row, revision) {
        state = withContext(Dispatchers.IO) { inspectArtifactAvailability(fileManager, row) }
    }
    ArtifactFilePreviewBody(row, state)
    ArtifactLocateAction(row, state)
    TextButton(
        { deliveryOpen = true },
        enabled = state is ArtifactAvailability.Ready,
        modifier = Modifier.testTag("chat-artifact-delivery"),
    ) { Text(stringResource(R.string.files_delivery_actions)) }
    if (deliveryOpen) {
        ArtifactFileDialog(
            fileManager,
            row,
            onOpenSession = { deliveryOpen = false },
            onDismiss = { deliveryOpen = false },
        )
    }
}
