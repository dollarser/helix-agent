package com.helix.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.FileSourceKind
import com.helix.ui.shared.BlockPreviewKind
import com.helix.ui.shared.IndicatedLazyColumn
import com.helix.ui.shared.InlineDiagramBlock
import com.helix.ui.shared.indicatedVerticalScroll
import com.helix.ui.shared.svgFilePreview
import com.helix.feature.conversation.R as ConversationR

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesScreenState.FilesPreviewDialog(
    actions: FilesScreenActions,
    handoff: FileConversationHandoff? = null,
) {
    with(actions) {
        openFile?.let { file ->
            if (!file.isDirectory) {
                val currentPreview = preview
                val loaded = currentPreview as? FilePreviewState.Ready
                FilesViewer(
                    onDismissRequest = { openFile = null },
                    title = {
                        Column {
                            Text(file.name)
                            FileTextPageControls(this@FilesPreviewDialog)
                        }
                    },
                    text = {
                        SelectableViewerPage(viewerPage) {
                            IndicatedLazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("files-viewer-content"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val text = loaded?.text
                                val diagram =
                                    text != null && text.length <= 65536 &&
                                        svgFilePreview(file.name, loaded.info.mimeType, loaded.info.sizeBytes, text)
                                item {
                                    if (loaded?.imageTooLarge == true) {
                                        Text(
                                            str(R.string.files_image_too_large),
                                            Modifier.testTag("files-image-too-large"),
                                        )
                                    }
                                    if (loaded?.imageDecodeFailed == true) {
                                        Text(
                                            str(R.string.files_image_unreadable),
                                            Modifier.testTag("files-image-unreadable"),
                                        )
                                    }
                                    when {
                                        currentPreview is FilePreviewState.Loading -> {
                                            Text(
                                                str(R.string.egress_loading),
                                                modifier = Modifier.testTag("files-preview-loading"),
                                            )
                                        }

                                        currentPreview is FilePreviewState.Failed -> {
                                            Text(
                                                currentPreview.message,
                                                modifier = Modifier.testTag("files-preview-error"),
                                            )
                                        }

                                        loaded?.image != null -> {
                                            Image(
                                                bitmap = loaded.image,
                                                contentDescription = file.name,
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 320.dp)
                                                        .testTag("files-preview-image"),
                                            )
                                        }

                                        diagram -> {
                                            InlineDiagramBlock(requireNotNull(text), BlockPreviewKind.SVG)
                                        }

                                        text != null -> {
                                            Unit
                                        }

                                        loaded?.imageTooLarge == true || loaded?.imageDecodeFailed == true -> {
                                            Unit
                                        }

                                        else -> {
                                            Text(
                                                str(R.string.files_no_preview),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.testTag("files-preview-none"),
                                            )
                                        }
                                    }
                                }
                                if (text != null && !diagram) {
                                    val chunks = viewerTextChunks(text)
                                    items(chunks.size) { index ->
                                        Text(
                                            chunks[index],
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier =
                                                Modifier.testTag(
                                                    if (index ==
                                                        0
                                                    ) {
                                                        "files-preview-text"
                                                    } else {
                                                        "files-text-$index"
                                                    },
                                                ),
                                        )
                                    }
                                }
                                item {
                                    FileFavoriteAction(
                                        com.helix.core.workspace
                                            .FileScopePath(selectedScopeId, file.relativePath),
                                        false,
                                        actions,
                                    )
                                    if (handoff != null && loaded != null) {
                                        FileConversationActions(
                                            com.helix.core.workspace
                                                .FileScopePath(selectedScopeId, file.relativePath),
                                            actions.fileManager,
                                            handoff,
                                        )
                                    }
                                    loaded?.info?.let { meta ->
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                str(R.string.files_info_size, formatSize(meta.sizeBytes)),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                str(R.string.files_info_modified, formatTime(meta.mtimeEpochMillis)),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                str(R.string.files_info_type, meta.mimeType),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                str(
                                                    R.string.files_info_sha256,
                                                    meta.sha256 ?: str(R.string.files_sha_omitted),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.testTag("files-info-sha"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        // Two rows: the AlertDialog confirmButton is a single narrow strip; one row
                        // overflows it and clips the trailing buttons zero-width (device-verified:
                        // 导出 measured 0dp wide on the 1080px gate device and was unclickable).
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (canMutate) {
                                    TextButton(
                                        onClick = {
                                            openFile = null
                                            renameTarget = file
                                        },
                                        modifier = Modifier.testTag("files-action-rename"),
                                    ) {
                                        Text(str(R.string.files_rename))
                                    }
                                    TextButton(
                                        onClick = {
                                            copyMove =
                                                CopyMoveTarget(move = false, listOf(file.relativePath))
                                        },
                                        modifier = Modifier.testTag("files-action-copy"),
                                    ) {
                                        Text(str(ConversationR.string.files_copy))
                                    }
                                    TextButton(
                                        onClick = {
                                            copyMove =
                                                CopyMoveTarget(move = true, listOf(file.relativePath))
                                        },
                                        modifier = Modifier.testTag("files-action-move"),
                                    ) {
                                        Text(str(R.string.files_move))
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        openFile = null
                                        requestDelete(listOf(file.relativePath))
                                    },
                                    modifier = Modifier.testTag("files-action-trash"),
                                ) {
                                    Text(str(R.string.files_trash_action))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { share(file) },
                                    modifier = Modifier.testTag("files-action-share"),
                                ) {
                                    Text(str(R.string.files_share))
                                }
                                // HXA-058: the 导出 entry — Workspace files only (the HXA-044 export region
                                // gate: input/work/output; SAF/all-files sources are never export sources).
                                if (currentSource.kind == FileSourceKind.WORKSPACE) {
                                    TextButton(
                                        onClick = {
                                            exportFile = file
                                            exportResult = null
                                            exportOpen = true
                                        },
                                        modifier = Modifier.testTag("files-action-export"),
                                    ) {
                                        Text(str(R.string.files_export))
                                    }
                                }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { openFile = null },
                            modifier = Modifier.testTag("files-preview-close"),
                        ) {
                            Text(str(ConversationR.string.files_close))
                        }
                    },
                    modifier = Modifier.testTag("files-preview-dialog"),
                )
            }
        }
    }
}

/** Full-screen document viewer; closing preserves the directory composition and scroll state. */
@Composable
@Suppress("FunctionName", "LongParameterList")
private fun FilesViewer(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            androidx.compose.ui.window
                .DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout
                        .Box(Modifier.weight(1f)) { title() }
                    dismissButton()
                }
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.foundation.layout
                    .Box(Modifier.weight(1f).fillMaxWidth()) { text() }
                androidx.compose.material3.HorizontalDivider()
                confirmButton()
            }
        }
    }
}
