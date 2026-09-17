package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.files.FileManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// HXA-203: the artifact file dialog's delivery actions, split out of ArtifactsScreen.kt
// (same package): the system "create document" picker, the outcome texts and the
// primary/secondary action rows. Every outcome shown here was produced by the real
// pipeline or the real OS call — the click itself never claims a result.

/**
 * The dialog's system "create document" picker, wired straight into the real export
 * pipeline ([startArtifactExport]) — the launcher's uri is the pipeline's destination;
 * returning without one starts nothing.
 */
@Composable
@Suppress("FunctionName")
internal fun artifactExportPicker(
    scope: CoroutineScope,
    fileManager: FileManagerService,
    row: ArtifactRowUi,
    cancelFlag: MutableState<Boolean>,
    exportState: MutableState<ArtifactExportState>,
): ActivityResultLauncher<String> =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(row.mediaType)) { uri ->
        if (uri != null) startArtifactExport(scope, fileManager, row, cancelFlag, exportState, uri)
    }

/** The dialog body: the availability/preview block plus the action outcomes. */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFileDialogContent(
    row: ArtifactRowUi,
    state: ArtifactAvailability,
    exportState: ArtifactExportState,
    external: ArtifactExternalOpenResult?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtifactFilePreviewBody(row, state)
        ArtifactActionOutcomes(row, exportState, external)
    }
}

/**
 * The dialog's "open with another app" action, off the main thread (staging a SAF artifact
 * copies it first); the honest [ArtifactExternalOpenResult] lands in the dialog's state.
 */
internal fun artifactExternalOpener(
    context: Context,
    scope: CoroutineScope,
    fileManager: FileManagerService,
    scopeId: String,
    relativePath: String,
    external: MutableState<ArtifactExternalOpenResult?>,
): () -> Unit =
    {
        external.value = null
        scope.launch {
            external.value =
                withContext(Dispatchers.IO) {
                    openArtifactExternal(context, fileManager, scopeId, relativePath)
                }
        }
    }

/**
 * The dialog's action outcomes (HXA-203): the export result ONLY as the pipeline reported
 * it (verified / size-checked / platform-confirmed detail; the cancelled-partial wording
 * for a cancelled run) and the external-open result (launched / no viewer / failed).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactActionOutcomes(
    row: ArtifactRowUi,
    exportState: ArtifactExportState,
    external: ArtifactExternalOpenResult?,
) {
    when (val e = exportState) {
        ArtifactExportState.Idle, ArtifactExportState.Running -> {
            Unit
        }

        is ArtifactExportState.Completed -> {
            Text(
                e.detail.ifEmpty { stringResource(R.string.artifacts_exported) },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("artifact-file-exported-${row.id}"),
            )
        }

        ArtifactExportState.Cancelled -> {
            Text(
                stringResource(R.string.files_export_cancelled_partial),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("artifact-file-export-cancelled-${row.id}"),
            )
        }

        is ArtifactExportState.Failed -> {
            Text(
                e.detail.ifEmpty { stringResource(R.string.files_export_failed) },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("artifact-file-export-failed-${row.id}"),
            )
        }
    }
    external?.let { r ->
        Text(
            stringResource(externalOpenOutcomeRes(r)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("artifact-file-external-${row.id}"),
        )
    }
}

/** The external-open outcome text; NoViewer and Failed are distinct honest states. */
private fun externalOpenOutcomeRes(result: ArtifactExternalOpenResult): Int =
    when (result) {
        ArtifactExternalOpenResult.Launched -> R.string.artifacts_open_external_launched
        ArtifactExternalOpenResult.NoViewer -> R.string.artifacts_open_external_no_viewer
        ArtifactExternalOpenResult.Failed -> R.string.artifacts_open_external_failed
    }

/**
 * The dialog's primary actions: Export (workspace files in an exportable region only, via
 * the system "create document" picker; the outcome is the pipeline's, not the click's) with
 * a cancel during the run, plus Share (text preview only).
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFilePrimaryActions(
    context: Context,
    row: ArtifactRowUi,
    state: ArtifactAvailability,
    exportState: ArtifactExportState,
    exportable: Boolean,
    onExportClick: () -> Unit,
    onCancelExport: () -> Unit,
) {
    Row {
        if (exportable) {
            TextButton(
                enabled = exportState !is ArtifactExportState.Running,
                modifier = Modifier.testTag("artifact-file-export-${row.id}"),
                onClick = onExportClick,
            ) {
                Text(
                    stringResource(
                        if (exportState is ArtifactExportState.Running) {
                            R.string.files_exporting_plain
                        } else {
                            R.string.files_export
                        },
                    ),
                )
            }
            if (exportState is ArtifactExportState.Running) {
                TextButton(
                    modifier = Modifier.testTag("artifact-file-export-cancel-${row.id}"),
                    onClick = onCancelExport,
                ) {
                    Text(stringResource(R.string.artifacts_export_cancel))
                }
            }
        }
        val shareText = (state as? ArtifactAvailability.Ready)?.text
        TextButton(
            enabled = shareText != null,
            modifier = Modifier.testTag("artifact-file-share-${row.id}"),
            onClick = { sharePlainText(context, shareText!!) },
        ) {
            Text(stringResource(R.string.artifacts_share))
        }
    }
}

/**
 * The dialog's secondary actions: open with another app (any Ready file — SAF files stage a
 * private copy first; the OS decides the viewer, and a missing viewer is reported), the
 * producing task (HXA-203: 支持返回产生它的任务), the source session, and close.
 */
@Composable
@Suppress("FunctionName")
internal fun ArtifactFileSecondaryActions(
    row: ArtifactRowUi,
    canOpenExternal: Boolean,
    onOpenExternal: () -> Unit,
    onOpenTask: ((String) -> Unit)?,
    onOpenSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row {
        if (canOpenExternal) {
            TextButton(
                modifier = Modifier.testTag("artifact-file-open-external-${row.id}"),
                onClick = onOpenExternal,
            ) {
                Text(stringResource(R.string.artifacts_open_external))
            }
        }
        if (row.turnId != null && onOpenTask != null) {
            TextButton(
                modifier = Modifier.testTag("artifact-file-open-task-${row.id}"),
                onClick = { onOpenTask(row.turnId) },
            ) {
                Text(stringResource(R.string.artifacts_open_task))
            }
        }
        TextButton(onClick = onOpenSession, modifier = Modifier.testTag("artifact-file-open-${row.id}")) {
            Text(stringResource(R.string.artifacts_open))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("artifact-file-close-${row.id}")) {
            Text(stringResource(R.string.goal_close))
        }
    }
}

/** A plain-text ACTION_SEND chooser; no share target in a fixture stays put (fail closed). */
@Suppress("SwallowedException")
internal fun sharePlainText(
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
