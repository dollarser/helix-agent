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
    onDismiss: () -> Unit,
) {
    val sessionId = screen.openSessionId
    if (fileManager == null || sessionId == null) return
    var rows by remember(sessionId) { mutableStateOf(emptyList<ArtifactRowUi>()) }
    var sources by remember(sessionId) { mutableStateOf(emptyList<ArtifactRowUi>()) }
    var showingSources by remember(sessionId) { mutableStateOf(false) }
    var selectedId by remember(sessionId) { mutableStateOf<String?>(null) }
    var revision by remember(sessionId) { mutableIntStateOf(0) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    // Refresh when a tool result settles, a turn finishes, the app resumes or the user opens
    // the panel. Streamed assistant tokens do not cause reads. Session changes cancel old reads.
    LaunchedEffect(sessionId, screen.toolTimeline, screen.isSending, revision) {
        try {
            rows = service.conversationArtifacts(sessionId)
            sources = service.conversationDocumentSources(sessionId)
            loaded = true
            failed = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    if (!loaded && !failed) return
    run {
        val displayedRows = if (showingSources) sources else rows
        val listState = rememberLazyListState()
        val selected = displayedRows.firstOrNull { it.id == selectedId }
        ConversationSheet(
            title = conversationArtifactTitle(selected, showingSources),
            tag = "chat-artifacts",
            onDismiss = onDismiss,
            scrollContent = false,
        ) {
            if (failed) {
                Text(stringResource(R.string.common_operation_failed), Modifier.testTag("chat-artifacts-error"))
                TextButton({
                    revision++
                }, Modifier.testTag("chat-artifacts-retry")) { Text(stringResource(R.string.session_settings_retry)) }
            } else if (selected == null) {
                ArtifactSourceSwitch(sources.size) { showingSources = it }
                if (displayedRows.isEmpty()) {
                    Text(stringResource(R.string.chat_artifacts_empty), Modifier.testTag("chat-artifacts-empty"))
                } else {
                    IndicatedLazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("chat-artifacts-list"),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(displayedRows, key = { it.id }) { row ->
                            ArtifactFileRowView(row, onOpen = { selectedId = row.id })
                        }
                    }
                }
            } else {
                TextButton(
                    onClick = { selectedId = null },
                    modifier = Modifier.testTag("chat-artifacts-back"),
                ) { Text(stringResource(R.string.chat_artifacts_back)) }
                if (showingSources) {
                    Text(
                        stringResource(R.string.document_source_preview_note),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "SHA-256: ${selected.sha256}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
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

@Composable
@Suppress("FunctionName")
private fun ArtifactSourceSwitch(
    sourceCount: Int,
    onSource: (Boolean) -> Unit,
) {
    if (sourceCount > 0) {
        androidx.compose.foundation.layout.Row {
            TextButton({ onSource(false) }, Modifier.testTag("chat-artifacts-outputs")) {
                Text(stringResource(R.string.chat_artifacts_entry))
            }
            TextButton({ onSource(true) }, Modifier.testTag("chat-document-sources")) {
                Text(stringResource(R.string.document_sources_count, sourceCount))
            }
        }
    }
}

@Composable
private fun conversationArtifactTitle(
    selected: ArtifactRowUi?,
    showingSources: Boolean,
): String =
    selected?.fileName ?: stringResource(
        if (showingSources) R.string.document_sources_title else R.string.chat_artifacts_entry,
    )
