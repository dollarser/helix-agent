package com.helix.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.helix.app.files.SortKey

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun FilesScreenLayout(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    with(actions) {
        with(state) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).testTag("screen-files"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AdaptiveFileControls(
                    location = "${currentSource.displayName} · /$currentPath",
                ) {
                    // 来源标识: switchable source chips + the always-shown current source.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        sources.forEach { source ->
                            TextButton(
                                onClick = {
                                    if (source.scopeId != selectedScopeId) {
                                        selectedScopeId = source.scopeId
                                        currentPath = ""
                                        trashOpen = false
                                    }
                                },
                                modifier = Modifier.testTag("files-source-${source.scopeId}"),
                            ) {
                                Text(source.displayName)
                            }
                        }
                    }
                    Text(
                        str(R.string.files_current_source, currentSource.displayName) +
                            if (canMutate) "" else str(R.string.files_read_only_suffix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("files-source-current"),
                    )

                    // 路径面包屑 + 排序 + 视图 + 工具按钮.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("files-breadcrumb"),
                        ) {
                            BreadcrumbCrumb(str(R.string.files_root_directory), isRoot = true, onClick = {
                                currentPath =
                                    ""
                            })
                            currentPath
                                .split("/")
                                .filter { it.isNotEmpty() }
                                .mapIndexed { index, segment ->
                                    val prefix =
                                        currentPath
                                            .split("/")
                                            .filter { it.isNotEmpty() }
                                            .take(index + 1)
                                            .joinToString("/")
                                    BreadcrumbCrumb(segment, isRoot = false, onClick = { currentPath = prefix })
                                }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SortButton(str(R.string.files_sort_name), SortKey.NAME, sortKey, onPick = { sortKey = it })
                            SortButton(str(R.string.files_sort_time), SortKey.TIME, sortKey, onPick = { sortKey = it })
                            SortButton(str(R.string.files_sort_size), SortKey.SIZE, sortKey, onPick = { sortKey = it })
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = { viewMode = ViewMode.LIST },
                                modifier = Modifier.testTag("files-view-list"),
                            ) {
                                Text(str(R.string.files_view_list))
                            }
                            TextButton(
                                onClick = { viewMode = ViewMode.GRID },
                                modifier = Modifier.testTag("files-view-grid"),
                            ) {
                                Text(str(R.string.files_view_grid))
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = { trashOpen = true },
                                modifier = Modifier.testTag("files-trash-open"),
                            ) {
                                Text(str(R.string.files_trash_button))
                            }
                            if (canMutate) {
                                TextButton(
                                    onClick = { newFolderOpen = true },
                                    modifier = Modifier.testTag("files-newfolder"),
                                ) {
                                    Text(str(R.string.files_new_folder_button))
                                }
                            }
                            // HXA-057: the visible 重新授权 / 移除 entry for SAF tree scopes.
                            TextButton(
                                onClick = { safPanelOpen = true },
                                modifier = Modifier.testTag("files-saf-open"),
                            ) {
                                Text(str(R.string.files_saf_button))
                            }
                            // HXA-058: the 导入 entry (a single document or a folder into the Workspace).
                            TextButton(
                                onClick = {
                                    importResult = null
                                    importOpen = true
                                },
                                modifier = Modifier.testTag("files-import-open"),
                            ) {
                                Text(str(R.string.files_import_button))
                            }
                        }
                    }
                }

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
                    } else if (entries.isEmpty()) {
                        Text(
                            str(R.string.files_empty_directory),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("files-empty"),
                        )
                    } else if (viewMode == ViewMode.LIST) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            entries.forEach { entry ->
                                FileRow(
                                    entry,
                                    selected.contains(entry.relativePath),
                                    onToggle = { toggleSelect(entry) },
                                    onClick = { onEntry(entry) },
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
                            items(entries) { entry ->
                                GridFileItem(
                                    entry,
                                    selected.contains(entry.relativePath),
                                    onToggle = { toggleSelect(entry) },
                                    onClick = { onEntry(entry) },
                                )
                            }
                        }
                    }
                }

                // 多选 action bar.
                if (selected.isNotEmpty() && !trashOpen && canMutate) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            str(R.string.files_selected_count, selected.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val selectedRels = selected.toList()
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
                            onClick = { startTrash(selectedRels) },
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
