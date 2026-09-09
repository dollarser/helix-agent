package com.helix.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesScreenLayout(
    state: FilesScreenState,
    actions: FilesScreenActions,
    onPermissions: () -> Unit,
) {
    if (state.homeOpen) {
        FilesHome(state, actions, onPermissions)
        return
    }
    with(actions) {
        with(state) {
            val visible = visibleEntries
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("screen-files"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilesLocationBar(state, actions)

                // 长操作进度/取消 + 状态 + 部分失败清单.
                if (batchBusy) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            batchLabel ?: str(R.string.files_batch_in_progress),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("files-batch-progress"),
                        )
                        TextButton(
                            onClick = { cancelFlag.set(true) },
                            modifier = Modifier.testTag("files-batch-cancel"),
                        ) {
                            Text(str(R.string.common_cancel))
                        }
                    }
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("files-status"))
                }
                if (batchFailures.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.testTag("files-failure-list"),
                    ) {
                        batchFailures.take(MAX_FAILURE_DETAIL_LINES).forEach { item ->
                            val detail = item.detail.ifBlank { "-" }
                            Text(
                                "· ${item.sourceRelativePath} — ${str(item.outcomeLabel())}：$detail",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (batchFailures.size > MAX_FAILURE_DETAIL_LINES) {
                            Text(
                                str(R.string.files_items_overflow, batchFailures.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Body: the trash panel, or the directory listing (list / grid).
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (trashOpen) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(str(R.string.files_trash_title), style = MaterialTheme.typography.titleMedium)
                                TextButton(
                                    onClick = { trashOpen = false },
                                    modifier = Modifier.testTag("files-trash-back"),
                                ) {
                                    Text(str(R.string.files_back))
                                }
                                if (canMutate) {
                                    TextButton(
                                        onClick = { emptyTrashPanel() },
                                        modifier = Modifier.testTag("files-trash-empty"),
                                    ) {
                                        Text(str(R.string.files_empty_trash))
                                    }
                                }
                            }
                            if (trashEntries.isEmpty()) {
                                Text(
                                    str(R.string.files_trash_empty_state),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("files-trash-empty-state"),
                                )
                            }
                            trashEntries.forEach { entry ->
                                TrashRow(
                                    entry,
                                    canMutate,
                                    onRestore = { restoreTrashEntry(it) },
                                    onPurge = { purgeTrashEntry(it) },
                                )
                            }
                        }
                    } else if (loadError != null) {
                        Text(
                            loadError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("files-error"),
                        )
                    } else if (visible.isEmpty()) {
                        Text(
                            str(
                                if (searchQuery.isBlank()) {
                                    R.string.files_empty_directory
                                } else {
                                    R.string.files_search_empty
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("files-empty"),
                        )
                    } else if (viewMode == ViewMode.LIST) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            visible.forEach { entry ->
                                FileRow(
                                    entry,
                                    selected.contains(entry.relativePath),
                                    selectionMode = selected.isNotEmpty(),
                                    onToggle = { if (canMutate) toggleSelect(entry) },
                                    onClick = { if (selected.isEmpty()) onEntry(entry) else toggleSelect(entry) },
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 96.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visible) { entry ->
                                GridFileItem(
                                    entry,
                                    selected.contains(entry.relativePath),
                                    selectionMode = selected.isNotEmpty(),
                                    onToggle = { if (canMutate) toggleSelect(entry) },
                                    onClick = { if (selected.isEmpty()) onEntry(entry) else toggleSelect(entry) },
                                )
                            }
                        }
                    }
                }

                // 多选 action bar.
                if (selected.isNotEmpty() && !trashOpen && canMutate) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            str(R.string.files_selected_count, selected.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val selectedRels = selected.toList()
                        if (selectedRels.size == 1) {
                            TextButton(
                                onClick = {
                                    renameTarget =
                                        entries.firstOrNull { it.relativePath == selectedRels.single() }
                                },
                                modifier = Modifier.testTag("files-batch-rename"),
                            ) { Text(str(R.string.files_rename)) }
                        }
                        TextButton(
                            onClick = { copyMove = CopyMoveTarget(move = false, selectedRels) },
                            modifier = Modifier.testTag("files-batch-copy"),
                        ) {
                            Text(str(R.string.files_copy))
                        }
                        TextButton(
                            onClick = { copyMove = CopyMoveTarget(move = true, selectedRels) },
                            modifier = Modifier.testTag("files-batch-move"),
                        ) {
                            Text(str(R.string.files_move))
                        }
                        TextButton(
                            onClick = { requestDelete(selectedRels) },
                            modifier = Modifier.testTag("files-batch-trash"),
                        ) {
                            Text(str(R.string.files_delete))
                        }
                        TextButton(
                            onClick = { selected = emptySet() },
                            modifier = Modifier.testTag("files-batch-clear"),
                        ) {
                            Text(str(R.string.files_deselect))
                        }
                    }
                }
            }
        }
    }
}
