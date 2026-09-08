package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.ExportTarget

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesExportDialog(
    state: FilesScreenState,
    actions: FilesScreenActions,
    onPickDocument: (String) -> Unit,
) {
    with(actions) {
        with(state) {
            if (exportOpen) {
                val exportFileEntry = exportFile
                AlertDialog(
                    onDismissRequest = {
                        if (!exportBusy) {
                            exportOpen = false
                            exportFile = null
                        }
                    },
                    title = { Text(str(R.string.files_export_title, exportFileEntry?.name ?: "")) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!exportBusy && exportResult == null) {
                                Text(
                                    str(
                                        R.string.files_export_source_line,
                                        exportFileEntry?.relativePath.orEmpty(),
                                        fileInfo?.let {
                                            str(
                                                R.string.files_export_source_size,
                                                formatSize(it.sizeBytes),
                                            )
                                        }
                                            ?: "",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-export-source"),
                                )
                                Text(
                                    str(R.string.files_export_source_label),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { exportMode = ExportMode.NEW_DOC },
                                        modifier = Modifier.testTag("files-export-newdoc"),
                                    ) {
                                        Text(
                                            if (exportMode == ExportMode.NEW_DOC) {
                                                str(R.string.files_export_newdoc_selected)
                                            } else {
                                                str(R.string.files_export_newdoc)
                                            },
                                        )
                                    }
                                    TextButton(
                                        onClick = { exportMode = ExportMode.TREE },
                                        modifier = Modifier.testTag("files-export-tree"),
                                    ) {
                                        Text(
                                            if (exportMode == ExportMode.TREE) {
                                                str(R.string.files_export_tree_selected)
                                            } else {
                                                str(R.string.files_export_tree)
                                            },
                                        )
                                    }
                                }
                                when (exportMode) {
                                    ExportMode.NEW_DOC -> {
                                        Text(
                                            str(R.string.files_export_newdoc_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }

                                    ExportMode.TREE -> {
                                        if (exportSources.isEmpty()) {
                                            Text(
                                                str(R.string.files_export_sources_empty_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.testTag("files-export-sources-empty"),
                                            )
                                        }
                                        exportSources.forEach { source ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier =
                                                    Modifier.fillMaxWidth().testTag(
                                                        "files-export-scope-${source.scopeId}",
                                                    ),
                                            ) {
                                                Text(
                                                    if (exportScopeId ==
                                                        source.scopeId
                                                    ) {
                                                        "● ${source.displayName}"
                                                    } else {
                                                        source.displayName
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                TextButton(
                                                    onClick = { exportScopeId = source.scopeId },
                                                    modifier =
                                                        Modifier.testTag(
                                                            "files-export-scope-pick-${source.scopeId}",
                                                        ),
                                                ) {
                                                    Text(str(R.string.files_select))
                                                }
                                            }
                                        }
                                        OutlinedTextField(
                                            value = exportParent,
                                            onValueChange = { exportParent = it },
                                            label = { Text(str(R.string.files_export_parent_field)) },
                                            singleLine = true,
                                            modifier = Modifier.testTag("files-export-parent"),
                                        )
                                        Text(
                                            str(R.string.files_conflict_policy),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            ConflictPolicy.entries.forEach { p ->
                                                TextButton(
                                                    onClick = { exportPolicy = p },
                                                    modifier = Modifier.testTag("files-export-policy-${p.name}"),
                                                ) {
                                                    Text(str(policyLabel(p)))
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (exportBusy) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().testTag("files-export-progress"),
                                )
                                Text(
                                    exportLabel ?: str(R.string.files_exporting_plain),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("files-export-label"),
                                )
                            } else {
                                exportResult?.let { TransferResultPanel(it, "files-export-result", actions) }
                            }
                        }
                    },
                    confirmButton = {
                        when {
                            exportBusy -> {
                                TextButton(
                                    onClick = { exportCancel.set(true) },
                                    modifier = Modifier.testTag("files-export-cancel"),
                                ) {
                                    Text(str(R.string.common_cancel))
                                }
                            }

                            exportResult != null -> {
                                TextButton(
                                    onClick = {
                                        exportOpen = false
                                        exportFile = null
                                    },
                                    modifier = Modifier.testTag("files-export-close"),
                                ) {
                                    Text(str(R.string.files_close))
                                }
                            }

                            else -> {
                                TextButton(
                                    onClick = {
                                        when (exportMode) {
                                            ExportMode.NEW_DOC -> {
                                                val mime =
                                                    fileInfo?.mimeType?.ifEmpty { null } ?: "application/octet-stream"
                                                onPickDocument(mime)
                                            }

                                            ExportMode.TREE -> {
                                                val scopeId = exportScopeId
                                                val file = exportFile
                                                if (scopeId == null) {
                                                    status = str(R.string.files_export_scope_required)
                                                    return@TextButton
                                                }
                                                if (file == null) {
                                                    exportOpen = false
                                                    return@TextButton
                                                }
                                                runExport(
                                                    file.relativePath,
                                                    ExportTarget.TreeDestination(scopeId, exportParent.trim()),
                                                    exportPolicy,
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("files-export-confirm"),
                                ) {
                                    Text(str(R.string.files_export_choose_and_export))
                                }
                            }
                        }
                    },
                    dismissButton = {
                        if (!exportBusy) {
                            TextButton(
                                onClick = {
                                    exportOpen = false
                                    exportFile = null
                                },
                                modifier = Modifier.testTag("files-export-dismiss"),
                            ) {
                                Text(str(R.string.files_close))
                            }
                        }
                    },
                    modifier = Modifier.testTag("files-export-dialog"),
                )
            }
        }
    }
}
