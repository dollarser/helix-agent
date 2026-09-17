package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.BackgroundTaskUi
import com.helix.app.chat.ChatService
import com.helix.app.chat.MessageUi
import com.helix.app.files.FileManagerService
import com.helix.core.model.TurnState
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Artifact Center (P0-B, research doc section 28 / PX-02 "结果可交付"): one first-class page
 * with TWO honest entry kinds. The FILES section lists real artifact rows (doc 02 §8) — files the
 * agent's tools actually wrote, from the `artifacts` table, with source session and size; the
 * RESULTS section lists every terminal turn across sessions. Rows are shared [ChatService]
 * StateFlows kept live as turns and files land in any session; entry and an explicit refresh
 * re-read storage onto those flows (off the main thread) so just-finished work is visible on
 * open. A file row opens a view that checks the file's availability AT OPEN — the row outlives
 * the file — with an in-app bounded preview (text or image, same facade as the Files page).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactsScreenDestination(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
) {
    val service = container.chatService
    val fileManager = container.fileManager
    var revision by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<BackgroundTaskUi?>(null) }
    var selectedFile by remember { mutableStateOf<ArtifactRowUi?>(null) }

    // Entry and explicit refresh re-read storage onto the shared flows (Room reads on the
    // service work scope), so a task finished while the app was closed — or a file written
    // directly — is visible on open; the shared StateFlows then keep both lists live, so no
    // separate per-screen query is needed.
    LaunchedEffect(revision) {
        service.refreshBackgroundTasksNow()
        service.refreshTaskDashboardsNow()
    }

    val allTasks by service.backgroundTasks.collectAsStateWithLifecycle()
    val taskRows: List<BackgroundTaskUi> = allTasks.filter { !it.running }
    val fileRows by service.artifactFiles.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().testTag("screen-artifacts")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("artifacts-header"),
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
            if (fileRows.isNotEmpty()) {
                item(key = "files-header") {
                    SectionHeader(stringResource(R.string.artifacts_files_section), "artifacts-section-files")
                }
                items(fileRows, key = ArtifactRowUi::id) { row ->
                    ArtifactFileRowView(row, onOpen = { selectedFile = row })
                }
            }
            if (taskRows.isNotEmpty()) {
                item(key = "results-header") {
                    SectionHeader(stringResource(R.string.artifacts_results_section), "artifacts-section-results")
                }
            }
            items(taskRows, key = BackgroundTaskUi::id) { row ->
                ArtifactRowView(row, onOpen = { selected = row })
            }
        }
    }
    selected?.let { s ->
        ArtifactResultDialog(service, s, { onOpenSession(s.sessionId) }, { selected = null })
    }
    selectedFile?.let { f ->
        ArtifactFileDialog(fileManager, f, { onOpenSession(f.sessionId) }, { selectedFile = null })
    }
}

/** A section label above one kind of artifact row. */
@Composable
@Suppress("FunctionName")
private fun SectionHeader(
    label: String,
    tag: String,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * One real artifact file row (doc 02 §8): the file name, size and type from the `artifacts`
 * row plus the source session's title. Tapping opens the file view (availability re-checked
 * there at open — the row outlives the file). Shared with the Tasks dashboard's task-artifact
 * dialog (HXA-202).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFileRowView(
    row: ArtifactRowUi,
    onOpen: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("artifact-file-row-${row.id}"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                row.fileName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("artifact-file-name-${row.id}"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatSize(row.sizeBytes)} · ${row.mediaType}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("artifact-file-meta-${row.id}"),
            )
            row.sessionTitle?.let { title ->
                Text(
                    stringResource(R.string.artifacts_file_source, title),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The file view's load outcome, checked against the REAL file at open (never the row alone). */
private sealed interface ArtifactFilePreviewState {
    data object Loading : ArtifactFilePreviewState

    /** The file is gone (deleted / moved / trash-purged) — an honest invalidation reason. */
    data object Missing : ArtifactFilePreviewState

    /** The file could not be inspected at all (scope unavailable, I/O failure). */
    data object Failed : ArtifactFilePreviewState

    data class Ready(
        val meta: FileManagerService.FileMeta,
        val text: String?,
        val imageBytes: ByteArray,
    ) : ArtifactFilePreviewState
}

/**
 * The file view for one real artifact row: re-checks the file at open (a row outlives its
 * file — the file can be trashed or edited independently), then shows an in-app bounded
 * preview through the SAME facade the Files page uses (never a raw path to the model) plus
 * honest actions — Share (text only, ACTION_SEND) and Open (the source session). Never
 * mutates the file. Shared with the Tasks dashboard's task-artifact dialog (HXA-202).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFileDialog(
    fileManager: FileManagerService,
    row: ArtifactRowUi,
    onOpenSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember(row.id) { mutableStateOf<ArtifactFilePreviewState>(ArtifactFilePreviewState.Loading) }
    LaunchedEffect(row.id) {
        state =
            withContext(Dispatchers.IO) {
                runCatching {
                    val scopePath = FileScopePath.fromModelReference(row.relativePath)
                    val scopeId = scopePath.scopeId
                    val relPath = scopePath.relativePath
                    val meta = fileManager.fileInfo(scopeId, relPath)
                    if (meta.sizeBytes < 0) {
                        ArtifactFilePreviewState.Missing
                    } else {
                        ArtifactFilePreviewState.Ready(
                            meta = meta,
                            text = if (meta.isText) fileManager.previewText(scopeId, relPath) else null,
                            imageBytes =
                                if (meta.mimeType.startsWith("image/")) {
                                    fileManager.previewImageBytes(scopeId, relPath)
                                } else {
                                    ByteArray(0)
                                },
                        )
                    }
                }.getOrElse { ArtifactFilePreviewState.Failed }
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.fileName) },
        text = { ArtifactFilePreviewBody(row, state) },
        confirmButton = {
            val shareText = (state as? ArtifactFilePreviewState.Ready)?.text
            TextButton(
                enabled = shareText != null,
                modifier = Modifier.testTag("artifact-file-share-${row.id}"),
                onClick = { sharePlainText(context, shareText!!) },
            ) {
                Text(stringResource(R.string.artifacts_share))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenSession, modifier = Modifier.testTag("artifact-file-open-${row.id}")) {
                    Text(stringResource(R.string.artifacts_open))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("artifact-file-close-${row.id}")) {
                    Text(stringResource(R.string.goal_close))
                }
            }
        },
        modifier = Modifier.testTag("artifact-file-dialog"),
    )
}

/** The dialog body: size + type, then the honest state — missing, unavailable, or a preview. */
@Composable
@Suppress("FunctionName")
private fun ArtifactFilePreviewBody(
    row: ArtifactRowUi,
    state: ArtifactFilePreviewState,
) {
    Column(
        Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val s = state) {
            ArtifactFilePreviewState.Loading -> {
                Text(stringResource(R.string.egress_loading))
            }

            ArtifactFilePreviewState.Missing -> {
                Text(
                    stringResource(R.string.artifacts_file_missing),
                    modifier = Modifier.testTag("artifact-file-missing"),
                )
            }

            ArtifactFilePreviewState.Failed -> {
                Text(
                    stringResource(R.string.artifacts_file_unavailable),
                    modifier = Modifier.testTag("artifact-file-unavailable"),
                )
            }

            is ArtifactFilePreviewState.Ready -> {
                Text(
                    "${stringResource(R.string.files_info_size, formatSize(s.meta.sizeBytes))} · " +
                        stringResource(R.string.files_info_type, s.meta.mimeType),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("artifact-file-info"),
                )
                val bitmap =
                    s.imageBytes.takeIf { it.isNotEmpty() }?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = row.fileName,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .testTag("artifact-file-image"),
                    )
                } else if (s.text != null) {
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("artifact-file-preview").padding(4.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.files_no_preview),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
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
                onClick = { result?.let { sharePlainText(context, it.joinToString("\n") { m -> m.content }) } },
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

/** A plain-text ACTION_SEND chooser; no share target in a fixture stays put (fail closed). */
@Suppress("SwallowedException")
private fun sharePlainText(
    context: Context,
    text: String,
) {
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
