package com.helix.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.SortKey

@Composable
@Suppress("FunctionName")
internal fun FilesLocationBar(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FileIconButton(R.drawable.ic_files_home, R.string.files_home, "files-home-open") { state.homeOpen = true }
            Box(Modifier.weight(1f)) {
                TextButton({ state.sourcesOpen = true }, modifier = Modifier.testTag("files-location-picker")) {
                    Text(state.currentSource.displayName + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(state.sourcesOpen, { state.sourcesOpen = false }) {
                    state.sources.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(source.displayName) },
                            onClick = { state.openLocation(source.scopeId) },
                            modifier = Modifier.testTag("files-source-${source.scopeId}"),
                        )
                    }
                }
            }
            FileIconButton(R.drawable.ic_files_search, R.string.files_search, "files-search-open") {
                state.searchOpen = !state.searchOpen
                state.searchQuery = ""
            }
            FileIconButton(R.drawable.ic_chat_more, R.string.files_controls, "files-controls-open") {
                state.controlsOpen =
                    true
            }
        }
        if (state.searchOpen) {
            OutlinedTextField(
                state.searchQuery,
                { state.searchQuery = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.files_search)) },
                modifier = Modifier.fillMaxWidth().testTag("files-search-query"),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).testTag("files-breadcrumb"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BreadcrumbCrumb(stringResource(R.string.files_root_directory), true) { state.currentPath = "" }
            val parts = state.currentPath.split('/').filter { it.isNotEmpty() }
            parts.forEachIndexed { index, part ->
                BreadcrumbCrumb(part, false) { state.currentPath = parts.take(index + 1).joinToString("/") }
            }
        }
        Text(
            stringResource(R.string.files_current_source, state.currentSource.displayName) + " · " +
                stringResource(R.string.files_directory_count, state.visibleEntries.size) +
                if (state.canMutate) "" else stringResource(R.string.files_read_only_suffix),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("files-source-current"),
        )
    }
    if (state.controlsOpen) FilesOptionsDialog(state, actions)
}

@Composable
@Suppress("FunctionName")
internal fun FileIconButton(
    icon: Int,
    label: Int,
    tag: String,
    action: () -> Unit,
) {
    IconButton(action, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(painterResource(icon), stringResource(label))
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun FilesOptionsDialog(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    fun choose(action: () -> Unit) {
        state.controlsOpen = false
        action()
    }
    AlertDialog(
        onDismissRequest = { state.controlsOpen = false },
        title = { Text(stringResource(R.string.files_controls)) },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    SortKey.NAME to R.string.files_sort_name,
                    SortKey.TIME to R.string.files_sort_time,
                    SortKey.SIZE to R.string.files_sort_size,
                ).forEach { (key, label) ->
                    TextButton(
                        { choose { state.sortKey = key } },
                        modifier = Modifier.testTag("files-sort-${key.name}"),
                    ) {
                        Text(stringResource(label) + if (state.sortKey == key) " ✓" else "")
                    }
                }
                TextButton({
                    choose {
                        state.viewMode = ViewMode.LIST
                    }
                }, modifier = Modifier.testTag("files-view-list")) { Text(stringResource(R.string.files_view_list)) }
                TextButton({
                    choose {
                        state.viewMode = ViewMode.GRID
                    }
                }, modifier = Modifier.testTag("files-view-grid")) { Text(stringResource(R.string.files_view_grid)) }
                if (state.canMutate) {
                    TextButton(
                        {
                            choose {
                                state.newFolderOpen = true
                            }
                        },
                        modifier =
                            Modifier.testTag(
                                "files-newfolder",
                            ),
                    ) { Text(stringResource(R.string.files_new_folder_button)) }
                    if (state.currentSource.kind == com.helix.app.files.FileSourceKind.WORKSPACE) {
                        TextButton(
                            {
                                choose {
                                    state.trashOpen = true
                                }
                            },
                            modifier =
                                Modifier.testTag(
                                    "files-trash-open",
                                ),
                        ) { Text(stringResource(R.string.files_trash_button)) }
                    }
                }
                TextButton({
                    choose {
                        state.safPanelOpen = true
                    }
                }, modifier = Modifier.testTag("files-saf-open")) { Text(stringResource(R.string.files_saf_button)) }
                TextButton(
                    {
                        choose {
                            state.importResult = null
                            state.importOpen = true
                        }
                    },
                    modifier =
                        Modifier.testTag(
                            "files-import-open",
                        ),
                ) { Text(stringResource(R.string.files_import_button)) }
                TextButton({
                    choose {
                        state.reloadTick++
                    }
                }, modifier = Modifier.testTag("files-refresh")) { Text(stringResource(R.string.files_refresh)) }
            }
        },
        confirmButton = {
            TextButton({
                state.controlsOpen = false
            }, modifier = Modifier.testTag("files-controls-close")) { Text(actions.str(R.string.files_close)) }
        },
    )
}
