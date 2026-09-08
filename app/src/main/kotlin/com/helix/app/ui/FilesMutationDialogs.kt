package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.ConflictPolicy

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesMutationDialogs(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    with(actions) {
        with(state) {
            renameTarget?.let { target ->
                // Start empty: the user types the new name fresh (the label hints at it) rather than editing
                // a pre-filled name, which keeps the entry unambiguous.
                var newName by remember(target.relativePath) { mutableStateOf("") }
                val renameFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { renameFocus.requestFocus() }
                AlertDialog(
                    onDismissRequest = { renameTarget = null },
                    title = { Text(str(R.string.files_rename)) },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(str(R.string.files_new_name)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.focusRequester(renameFocus).testTag("files-rename-field"),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val chosen = newName.trim()
                                if (chosen.isEmpty()) {
                                    status = str(R.string.files_name_required)
                                    renameTarget = null
                                } else {
                                    val dir = target.relativePath.substringBeforeLast('/')
                                    val dstRel = if (dir.isEmpty()) chosen else "$dir/$chosen"
                                    renameTarget = null
                                    openFile = null
                                    doRename(target.relativePath, dstRel, overwrite = false)
                                }
                            },
                            modifier = Modifier.testTag("files-rename-confirm"),
                        ) {
                            Text(str(R.string.files_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { renameTarget = null },
                            modifier = Modifier.testTag("files-rename-cancel"),
                        ) {
                            Text(str(R.string.common_cancel))
                        }
                    },
                    modifier = Modifier.testTag("files-rename-dialog"),
                )
            }

            copyMove?.let { cm ->
                var destDir by remember { mutableStateOf(currentPath) }
                var policy by remember { mutableStateOf(ConflictPolicy.ASK) }
                AlertDialog(
                    onDismissRequest = { copyMove = null },
                    title = {
                        Text(
                            if (cm.move) str(R.string.files_move_selected) else str(R.string.files_copy_selected),
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = destDir,
                                onValueChange = { destDir = it },
                                label = { Text(str(R.string.files_dest_field)) },
                                singleLine = true,
                                modifier = Modifier.testTag("files-dest-field"),
                            )
                            Text(
                                str(R.string.files_conflict_policy),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ConflictPolicy.entries.forEach { p ->
                                    TextButton(
                                        onClick = { policy = p },
                                        modifier = Modifier.testTag("files-policy-${p.name}"),
                                    ) {
                                        Text(str(policyLabel(p)))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val srcs = cm.sourceRels
                                copyMove = null
                                startCopyMove(srcs, destDir.trim(), policy, cm.move)
                            },
                            modifier = Modifier.testTag("files-dest-confirm"),
                        ) {
                            Text(str(R.string.files_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { copyMove = null }, modifier = Modifier.testTag("files-dest-cancel")) {
                            Text(str(R.string.common_cancel))
                        }
                    },
                    modifier = Modifier.testTag("files-dest-dialog"),
                )
            }

            if (newFolderOpen) {
                var folderName by remember { mutableStateOf("") }
                val newFolderFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { newFolderFocus.requestFocus() }
                AlertDialog(
                    onDismissRequest = { newFolderOpen = false },
                    title = { Text(str(R.string.files_new_folder_title)) },
                    text = {
                        OutlinedTextField(
                            value = folderName,
                            onValueChange = { folderName = it },
                            label = { Text(str(R.string.files_folder_name_field)) },
                            singleLine = true,
                            modifier = Modifier.focusRequester(newFolderFocus).testTag("files-newfolder-field"),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val name = folderName.trim()
                                newFolderOpen = false
                                if (name.isEmpty()) {
                                    status = str(R.string.files_name_required)
                                    return@TextButton
                                }
                                createDirectory(name)
                            },
                            modifier = Modifier.testTag("files-newfolder-confirm"),
                        ) {
                            Text(str(R.string.files_create))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { newFolderOpen = false },
                            modifier = Modifier.testTag("files-newfolder-cancel"),
                        ) {
                            Text(str(R.string.common_cancel))
                        }
                    },
                    modifier = Modifier.testTag("files-newfolder-dialog"),
                )
            }

            conflictTarget?.let { (srcRel, dstRel) ->
                AlertDialog(
                    onDismissRequest = { conflictTarget = null },
                    title = { Text(str(R.string.files_conflict_title)) },
                    text = {
                        Text(
                            str(
                                R.string.files_conflict_message,
                                dstRel.substringAfterLast('/'),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val suggested = suggestedName
                                conflictTarget = null
                                if (suggested != null) doRename(srcRel, suggested, overwrite = false)
                            },
                            modifier = Modifier.testTag("files-conflict-rename"),
                        ) {
                            Text(str(R.string.files_rename))
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    conflictTarget = null
                                    doRename(srcRel, dstRel, overwrite = true)
                                },
                                modifier = Modifier.testTag("files-conflict-overwrite"),
                            ) {
                                Text(str(R.string.files_overwrite))
                            }
                            TextButton(
                                onClick = {
                                    conflictTarget = null
                                    status = str(R.string.files_skipped_status)
                                },
                                modifier = Modifier.testTag("files-conflict-skip"),
                            ) {
                                Text(str(R.string.files_skip))
                            }
                        }
                    },
                    modifier = Modifier.testTag("files-conflict-dialog"),
                )
            }
        }
    }
}
