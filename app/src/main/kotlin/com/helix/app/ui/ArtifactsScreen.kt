package com.helix.app.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.helix.app.ui.IndicatedLazyColumn
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.model.TurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Artifact Center (P0-B, research doc section 28 / PX-02 "结果可交付"): one first-class page
 * with TWO honest entry kinds. The FILES section lists real artifact rows (doc 02 §8) — files the
 * agent's tools actually wrote, from the `artifacts` table, with source session and size; the
 * RESULTS section lists completed turns across sessions; failures remain in Tasks. Rows are shared [ChatService]
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
    onOpenTask: (String) -> Unit = {},
    sessionFilter: Set<String>? = null,
    projectRecords: com.helix.app.projects.ProjectRecords? = null,
    onRefresh: (() -> Unit)? = null,
) {
    val service = container.chatService
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
    val taskRows: List<BackgroundTaskUi> =
        (projectRecords?.tasks ?: allTasks).filter {
            isDeliverableResult(it) &&
                (sessionFilter == null || it.sessionId in sessionFilter)
        }
    val allFiles by service.artifactFiles.collectAsStateWithLifecycle()
    val fileRows = (projectRecords?.files ?: allFiles).filter { sessionFilter == null || it.sessionId in sessionFilter }

    Column(Modifier.fillMaxSize().testTag("screen-artifacts")) {
        ArtifactsRefresh {
            revision += 1
            onRefresh?.invoke()
        }
        IndicatedLazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fileRows.isEmpty() && taskRows.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.empty_artifacts)) }
            }
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
    ArtifactSelection(container, selected, selectedFile, onOpenSession, onOpenTask) {
        selected = null
        selectedFile = null
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

/**
 * The file view for one real artifact row (HXA-203): re-checks the file at open (a row
 * outlives its file — the file can be trashed or edited independently; a revoked SAF grant
 * is its own honest state), shows a bounded preview through the SAME facade the Files page
 * uses, and offers the real delivery actions — Export (the same SAF pipeline the Files page
 * uses, outcome from the pipeline alone), Open with another app, Share (text only) and the
 * two ownership links: the source session and the producing task. Never mutates the file
 * and never claims an outcome that did not happen. Shared with the Tasks dashboard's
 * task-artifact dialog (HXA-202).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFileDialog(
    fileManager: FileManagerService,
    row: ArtifactRowUi,
    onOpenSession: () -> Unit,
    onOpenTask: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val view = remember(row.id) { ArtifactFilePresentation() }
    val scopePath = remember(row.id) { row.parsedScopePath() }
    // Only workspace files inside an exportable region get the in-app export path; SAF-scope
    // artifacts go through the "open with another app" staging instead (fail-closed, no
    // silent scope crossing).

    LaunchedEffect(row.id) {
        view.availability.value = withContext(Dispatchers.IO) { inspectArtifactAvailability(fileManager, row) }
    }
    val exportPicker =
        artifactExportPicker(coroutineScope, fileManager, row, view.cancel, view.export)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.fileName) },
        text = {
            ArtifactFileDialogContent(
                row,
                view.availability.value,
                view.export.value,
                view.external.value,
                fileManager,
            )
        },
        confirmButton = {
            ArtifactFilePrimaryActions(
                context,
                row,
                view.availability.value,
                view.export.value,
                row.canExportFile(),
                onExportClick = {
                    view.external.value = null
                    launchArtifactExportPicker(context, exportPicker, row.fileName, view.export)
                },
                onCancelExport = { view.cancel.value = true },
            )
        },
        dismissButton = {
            ArtifactFileSecondaryActions(
                row,
                canOpenExternal = scopePath != null && view.availability.value is ArtifactAvailability.Ready,
                onOpenExternal =
                    scopePath?.let { p ->
                        artifactExternalOpener(
                            context,
                            coroutineScope,
                            fileManager,
                            p.scopeId,
                            p.relativePath,
                            view.external,
                        )
                    } ?: {},
                onOpenTask,
                onOpenSession,
                onDismiss,
            )
        },
        modifier = Modifier.testTag("artifact-file-dialog"),
    )
}

/**
 * The dialog body: size + type, then the honest availability state — revoked, missing,
 * unavailable, or the [ArtifactReadyPreview] with a change banner and an explicit
 * truncation marker (HXA-203: 缺文件、撤权、内容变化分别显示，截断可见).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFilePreviewBody(
    row: ArtifactRowUi,
    state: ArtifactAvailability,
) {
    Column(
        Modifier.heightIn(max = 440.dp).indicatedVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val s = state) {
            ArtifactAvailability.Loading -> {
                Text(stringResource(R.string.egress_loading))
            }

            ArtifactAvailability.Missing -> {
                Text(
                    stringResource(R.string.artifacts_file_missing),
                    modifier = Modifier.testTag("artifact-file-missing"),
                )
            }

            ArtifactAvailability.Revoked -> {
                Text(
                    stringResource(R.string.artifacts_file_revoked),
                    modifier = Modifier.testTag("artifact-file-revoked"),
                )
            }

            ArtifactAvailability.Failed -> {
                Text(
                    stringResource(R.string.artifacts_file_unavailable),
                    modifier = Modifier.testTag("artifact-file-unavailable"),
                )
            }

            is ArtifactAvailability.Ready -> {
                ArtifactReadyPreview(row, s)
            }
        }
    }
}

/**
 * The ready file's content: size + type, the change banner (only on a POSITIVE size/hash
 * mismatch with the task's record), then an image, a bounded text preview with an explicit
 * truncation marker, or the no-preview hint with the two real escape routes (HXA-203).
 */
@Composable
@Suppress("FunctionName")
private fun ArtifactReadyPreview(
    row: ArtifactRowUi,
    ready: ArtifactAvailability.Ready,
) {
    Text(
        "${stringResource(R.string.files_info_size, formatSize(ready.meta.sizeBytes))} · " +
            stringResource(R.string.files_info_type, ready.meta.mimeType),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("artifact-file-info"),
    )
    if (ready.changed) {
        Text(
            stringResource(R.string.artifacts_file_changed),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("artifact-file-changed"),
        )
    }
    val bitmap by rememberArtifactBitmap(ready.imageBytes)
    val previewBitmap = bitmap
    if (previewBitmap != null) {
        Image(
            bitmap = previewBitmap,
            contentDescription = row.fileName,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .testTag("artifact-file-image"),
        )
    } else if (ready.text != null) {
        // The marker sits ABOVE the preview: below a 64 KiB monospace block inside the
        // dialog's bounded scroll column it would be off-screen and the truncation invisible.
        if (ready.textTruncated) {
            Text(
                stringResource(R.string.artifacts_file_preview_truncated),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("artifact-file-preview-truncated"),
            )
        }
        Text(
            ready.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag("artifact-file-preview").padding(4.dp),
        )
    } else {
        Text(
            stringResource(R.string.files_no_preview),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.artifacts_file_no_preview_hint),
            style = MaterialTheme.typography.bodySmall,
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
    shareFailed: Boolean,
    onRetry: () -> Unit,
) {
    SelectionContainer {
        Column(Modifier.heightIn(max = 440.dp).indicatedVerticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.artifacts_summary_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (failed) {
                Text(stringResource(R.string.background_task_result_missing))
                TextButton(onRetry, modifier = Modifier.testTag("artifact-result-retry")) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
            if (shareFailed) Text(stringResource(R.string.artifacts_share_unavailable))
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
internal fun ArtifactResultDialog(
    service: ChatService,
    row: BackgroundTaskUi,
    onOpenSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var result by remember(row.id) { mutableStateOf<List<MessageUi>?>(null) }
    var shareFailed by remember(row.id) { mutableStateOf(false) }
    var failed by remember(row.id) { mutableStateOf(false) }
    var retry by remember(row.id) { mutableStateOf(0) }
    LaunchedEffect(row.id, retry) {
        failed = false
        try {
            result = service.taskResult(row.id)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_task_result)) },
        text = {
            ArtifactResultText(result, failed, shareFailed) { retry++ }
        },
        confirmButton = {
            val shareText = result?.joinToString("\n") { it.content }
            TextButton(
                enabled = result != null && !failed,
                modifier = Modifier.testTag("artifact-share-${row.id}"),
                onClick = { shareFailed = !sharePlainText(context, shareText.orEmpty()) },
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
