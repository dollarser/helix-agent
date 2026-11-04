package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.helix.app.R
import com.helix.app.files.FileSourceKind
import com.helix.app.files.SortKey
import com.helix.core.workspace.FileScopePath

private enum class FilesOptionsPage { ROOT, ORDER, VIEW }

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun FilesOptionsDialog(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    var page by remember { mutableStateOf(FilesOptionsPage.ROOT) }
    val rootScroll = rememberScrollState()
    val choicesScroll = rememberScrollState()
    val title =
        when (page) {
            FilesOptionsPage.ROOT -> R.string.files_controls
            FilesOptionsPage.ORDER -> R.string.files_sort_order
            FilesOptionsPage.VIEW -> R.string.files_view
        }

    Dialog(onDismissRequest = {
        if (page == FilesOptionsPage.ROOT) state.controlsOpen = false else page = FilesOptionsPage.ROOT
    }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 8.dp).testTag("files-options-dialog")) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page != FilesOptionsPage.ROOT) {
                        IconButton(
                            { page = FilesOptionsPage.ROOT },
                            Modifier.size(48.dp).testTag("files-options-back"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_drawer_chevron),
                                stringResource(R.string.files_back),
                                Modifier.graphicsLayer { rotationZ = 180f },
                            )
                        }
                    }
                    Text(
                        stringResource(title),
                        Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton({ state.controlsOpen = false }, Modifier.size(48.dp).testTag("files-controls-close")) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.files_close))
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .indicatedVerticalScroll(if (page == FilesOptionsPage.ROOT) rootScroll else choicesScroll)
                        .testTag("files-options-content"),
                ) {
                    when (page) {
                        FilesOptionsPage.ROOT -> {
                            FilesRootOptions(state, actions) { page = it }
                        }

                        FilesOptionsPage.ORDER -> {
                            SortKey.entries.forEach { key ->
                                FilesChoiceRow(
                                    stringResource(sortLabel(key)),
                                    "files-sort-${key.name}",
                                    state.sortKey == key,
                                ) {
                                    state.chooseFileOption { state.sortKey = key }
                                }
                            }
                        }

                        FilesOptionsPage.VIEW -> {
                            ViewMode.entries.forEach { mode ->
                                FilesChoiceRow(
                                    stringResource(
                                        if (mode ==
                                            ViewMode.LIST
                                        ) {
                                            R.string.files_view_list
                                        } else {
                                            R.string.files_view_grid
                                        },
                                    ),
                                    "files-view-${mode.name.lowercase()}",
                                    state.viewMode == mode,
                                ) {
                                    state.chooseFileOption {
                                        state.viewMode =
                                            mode
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun FilesScreenState.chooseFileOption(action: () -> Unit) {
    controlsOpen = false
    action()
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun FilesRootOptions(
    state: FilesScreenState,
    actions: FilesScreenActions,
    open: (FilesOptionsPage) -> Unit,
) {
    FilesMenuRow(
        stringResource(R.string.files_sort_order),
        "files-sort-open",
        value = stringResource(sortLabel(state.sortKey)),
    ) { open(FilesOptionsPage.ORDER) }
    FilesMenuRow(
        stringResource(R.string.files_view),
        "files-view-open",
        value =
            stringResource(
                if (state.viewMode ==
                    ViewMode.LIST
                ) {
                    R.string.files_view_list
                } else {
                    R.string.files_view_grid
                },
            ),
    ) {
        open(FilesOptionsPage.VIEW)
    }
    MenuDivider()
    FileFavoriteAction(
        FileScopePath(state.selectedScopeId, state.currentPath),
        true,
        actions,
        menuItem = true,
        onSelected = { state.controlsOpen = false },
    )
    if (state.canMutate) {
        FilesMenuRow(stringResource(R.string.files_new_file), "files-newfile") {
            state.chooseFileOption {
                state.newFileOpen =
                    true
            }
        }
        FilesMenuRow(stringResource(R.string.files_new_folder_button), "files-newfolder") {
            state.chooseFileOption {
                state.newFolderOpen =
                    true
            }
        }
        if (state.currentSource.kind == FileSourceKind.WORKSPACE) {
            FilesMenuRow(stringResource(R.string.files_trash_button), "files-trash-open") {
                state.chooseFileOption {
                    state.trashOpen =
                        true
                }
            }
        }
    }
    if (state.currentSource.cleanupEligible) {
        FilesMenuRow(
            stringResource(R.string.files_workspace_cleanup),
            "files-workspace-cleanup",
        ) {
            state.chooseFileOption {
                state.cleanupTarget =
                    state.currentSource
            }
        }
    }
    MenuDivider()
    FilesMenuRow(stringResource(R.string.files_import_button), "files-import-open") {
        state.chooseFileOption {
            state.importResult = null
            state.importOpen = true
        }
    }
    FilesMenuRow(stringResource(R.string.files_saf_button), "files-saf-open") {
        state.chooseFileOption {
            state.safPanelOpen =
                true
        }
    }
    FilesMenuRow(
        stringResource(R.string.files_refresh),
        "files-refresh",
    ) { state.chooseFileOption { state.reloadTick++ } }
}

private fun sortLabel(key: SortKey): Int =
    when (key) {
        SortKey.NAME -> R.string.files_sort_name
        SortKey.TIME -> R.string.files_sort_time
        SortKey.SIZE -> R.string.files_sort_size
        SortKey.TYPE -> R.string.files_sort_type
    }

@Composable
@Suppress("FunctionName")
private fun MenuDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
@Suppress("FunctionName")
internal fun FilesMenuRow(
    label: String,
    tag: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(painterResource(R.drawable.ic_drawer_chevron), null, Modifier.size(20.dp))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun FilesChoiceRow(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected, onClick = null)
    }
}
