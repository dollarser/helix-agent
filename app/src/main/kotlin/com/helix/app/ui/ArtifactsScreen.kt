package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.app.chat.BackgroundTaskUi
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageUi
import com.helix.core.model.TurnState

/**
 * The Artifact Center (P0-B, research doc section 28 / PX-02 "结果可交付"): one first-class page
 * listing the deliverable results of finished tasks — every terminal turn across sessions — so
 * outcomes are reachable and shareable outside the chat. A row opens a result view with the
 * persisted summary plus honest Share / Open / Collect actions.
 *
 * Rows are the shared [ChatService.backgroundTasks] StateFlow filtered to terminal turns — the
 * same query the task dashboard uses, kept live as turns finish in any session. Entry and an
 * explicit refresh re-read storage onto that flow (off the main thread) so a just-finished task
 * is visible on open.
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactsScreenDestination(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
) {
    val service = container.chatService
    var revision by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<BackgroundTaskUi?>(null) }

    // Entry and explicit refresh re-read storage onto the shared [ChatService.backgroundTasks]
    // flow (a Room read on the service work scope), so a task finished while the app was closed
    // — or written directly — is visible on open; the shared StateFlow then keeps the list live
    // as turns terminalize in any session, so no separate per-screen query is needed.
    LaunchedEffect(revision) { service.refreshBackgroundTasksNow() }

    val allTasks by service.backgroundTasks.collectAsStateWithLifecycle()
    val rows: List<BackgroundTaskUi> = allTasks.filter { !it.running }

    Column(Modifier.fillMaxSize().testTag("screen-artifacts")) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("artifacts-header"),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { revision += 1 }, modifier = Modifier.testTag("artifacts-refresh")) {
                Text(stringResource(R.string.cap_refresh))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = BackgroundTaskUi::id) { row ->
                ArtifactRowView(row, onOpen = { selected = row })
            }
        }
    }
    selected?.let { s ->
        ArtifactResultDialog(
            service,
            s,
            onOpenSession = { onOpenSession(s.sessionId) },
            onDismiss = { selected = null },
        )
    }
}

/** One finished-task result: a tappable card with the session title and a live status chip. */
@Composable
@Suppress("FunctionName")
private fun ArtifactRowView(
    row: BackgroundTaskUi,
    onOpen: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("artifact-row-${row.id}"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("artifact-title-${row.id}"),
                )
                Text(
                    stringResource(statusResFor(row.state)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("artifact-state-${row.id}"),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpen, modifier = Modifier.testTag("artifact-view-${row.id}")) {
                Text(stringResource(R.string.background_task_result))
            }
        }
    }
}

/**
 * The scrollable body of a result view: the persisted summary (the turn's messages), or an
 * honest fallback when the result is unavailable or empty.
 */
@Composable
@Suppress("FunctionName")
private fun ArtifactResultText(
    result: List<MessageUi>?,
    failed: Boolean,
) {
    SelectionContainer {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.artifacts_summary_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (failed) {
                Text(stringResource(R.string.background_task_result_missing))
            }
            result?.forEach { MarkdownText(it.content) }
            if (result?.isEmpty() == true) {
                Text(stringResource(R.string.background_task_result_empty))
            }
        }
    }
}

/**
 * The result view for one finished task: the persisted summary (the turn's messages) plus
 * honest actions — Share (posts an ACTION_SEND), Open (returns to the source session) and
 * Collect (reclaim the result), mirroring the task dashboard's result dialog.
 */
@Composable
@Suppress("FunctionName", "SwallowedException")
private fun ArtifactResultDialog(
    service: ChatService,
    row: BackgroundTaskUi,
    onOpenSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var result by remember(row.id) { mutableStateOf<List<MessageUi>?>(null) }
    var failed by remember(row.id) { mutableStateOf(false) }
    LaunchedEffect(row.id) {
        try {
            result = service.taskResult(row.id)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_task_result)) },
        text = { ArtifactResultText(result, failed) },
        confirmButton = {
            TextButton(
                enabled = result != null && !failed,
                modifier = Modifier.testTag("artifact-share-${row.id}"),
                onClick = { result?.let { shareResultText(context, it) } },
            ) {
                Text(stringResource(R.string.artifacts_share))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenSession, modifier = Modifier.testTag("artifact-open-${row.id}")) {
                    Text(stringResource(R.string.artifacts_open))
                }
                TextButton(
                    enabled = row.canCollect,
                    onClick = {
                        service.collectTaskResult(row.id)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("artifact-collect-${row.id}"),
                ) {
                    Text(stringResource(R.string.background_task_collect))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("artifact-close-${row.id}")) {
                    Text(stringResource(R.string.goal_close))
                }
            }
        },
    )
}

/** A terminal turn's human status; anything unexpected reads as the neutral "interrupted". */
private fun statusResFor(state: TurnState): Int =
    when (state) {
        TurnState.COMPLETED -> R.string.artifacts_state_done
        TurnState.FAILED -> R.string.artifacts_state_failed
        TurnState.CANCELLED -> R.string.artifacts_state_cancelled
        else -> R.string.artifacts_state_interrupted
    }

/**
 * Posts a result as a plain-text ACTION_SEND chooser. A test fixture (or a device with no share
 * target) resolves to nothing: fail closed by staying put, never a crash.
 */
@Suppress("SwallowedException")
private fun shareResultText(
    context: Context,
    messages: List<MessageUi>,
) {
    val text = messages.joinToString("\n") { it.content }
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        // No share target resolves in the fixture: staying on the page is the correct outcome.
    }
}
