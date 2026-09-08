package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.ConflictPolicy

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesImportDialog(
    state: FilesScreenState,
    actions: FilesScreenActions,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
) {
    with(actions) {
        with(state) {
            if (importOpen) {
                AlertDialog(
                    onDismissRequest = {
                        if (!importBusy) importOpen = false
                    },
                    title = { Text(str(R.string.files_import_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!importBusy && importResult == null) {
                                Text(
                                    str(R.string.files_import_source_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { importMode = ImportMode.FILE },
                                        modifier = Modifier.testTag("files-import-file"),
                                    ) {
                                        Text(
                                            if (importMode == ImportMode.FILE) {
                                                str(R.string.files_import_single_selected)
                                            } else {
                                                str(R.string.files_import_single)
                                            },
                                        )
                                    }
                                    TextButton(
                                        onClick = { importMode = ImportMode.FOLDER },
                                        modifier = Modifier.testTag("files-import-folder"),
                                    ) {
                                        Text(
                                            if (importMode == ImportMode.FOLDER) {
                                                str(R.string.files_import_folder_selected)
                                            } else {
                                                str(R.string.files_import_folder)
                                            },
                                        )
                                    }
                                }
                                Text(
                                    str(R.string.files_import_target),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-import-target"),
                                )
                                Text(
                                    str(R.string.files_conflict_policy),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ConflictPolicy.entries.forEach { p ->
                                        TextButton(
                                            onClick = { importPolicy = p },
                                            modifier = Modifier.testTag("files-import-policy-${p.name}"),
                                        ) {
                                            Text(str(policyLabel(p)))
                                        }
                                    }
                                }
                            } else if (importBusy) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().testTag("files-import-progress"),
                                )
                                Text(
                                    importLabel ?: str(R.string.files_importing_plain),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-import-label"),
                                )
                            } else {
                                importResult?.let { TransferResultPanel(it, "files-import-result", actions) }
                            }
                        }
                    },
                    confirmButton = {
                        when {
                            importBusy -> {
                                TextButton(
                                    onClick = { importCancel.set(true) },
                                    modifier = Modifier.testTag("files-import-cancel"),
                                ) {
                                    Text(str(R.string.common_cancel))
                                }
                            }

                            importResult != null -> {
                                TextButton(
                                    onClick = { importOpen = false },
                                    modifier = Modifier.testTag("files-import-close"),
                                ) {
                                    Text(str(R.string.files_close))
                                }
                            }

                            else -> {
                                TextButton(
                                    onClick = {
                                        if (importMode == ImportMode.FILE) {
                                            onPickFile()
                                        } else {
                                            onPickFolder()
                                        }
                                    },
                                    modifier = Modifier.testTag("files-import-confirm"),
                                ) {
                                    Text(str(R.string.files_import_choose_and_import))
                                }
                            }
                        }
                    },
                    dismissButton = {
                        if (!importBusy) {
                            TextButton(
                                onClick = { importOpen = false },
                                modifier = Modifier.testTag("files-import-dismiss"),
                            ) {
                                Text(str(R.string.files_close))
                            }
                        }
                    },
                    modifier = Modifier.testTag("files-import-dialog"),
                )
            }
        }
    }
}
