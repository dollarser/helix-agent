package com.helix.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesScreenState.FilesPreviewDialog(actions: FilesScreenActions) {
    with(actions) {
        openFile?.let { file ->
            if (!file.isDirectory) {
                val currentPreview = preview
                val loaded = currentPreview as? FilePreviewState.Ready
                AlertDialog(
                    onDismissRequest = { openFile = null },
                    title = { Text(file.name) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                currentPreview is FilePreviewState.Loading -> {
                                    Text(
                                        str(R.string.egress_loading),
                                        modifier = Modifier.testTag("files-preview-loading"),
                                    )
                                }

                                currentPreview is FilePreviewState.Failed -> {
                                    Text(currentPreview.message, modifier = Modifier.testTag("files-preview-error"))
                                }

                                loaded?.image != null -> {
                                    Image(
                                        bitmap = loaded.image,
                                        contentDescription = file.name,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 200.dp)
                                                .testTag("files-preview-image"),
                                    )
                                }

                                loaded?.text != null -> {
                                    Text(
                                        loaded.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier =
                                            Modifier
                                                .testTag("files-preview-text")
                                                .padding(4.dp),
                                    )
                                }

                                else -> {
                                    Text(
                                        str(R.string.files_no_preview),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("files-preview-none"),
                                    )
                                }
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
                                        Text(str(R.string.files_copy))
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
                            Text(str(R.string.files_close))
                        }
                    },
                    modifier = Modifier.testTag("files-preview-dialog"),
                )
            }
        }
    }
}
