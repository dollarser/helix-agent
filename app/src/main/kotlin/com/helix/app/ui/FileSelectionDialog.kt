package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helix.app.FeatureFiles
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSourceKind
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.SafTreeScopeService

typealias FileSelectionContent = @Composable ((FileScopePath) -> Unit, () -> Unit) -> Unit

/** A read-only presentation of the existing file manager, with no grant/mutation/Root controls. */
@Composable
@Suppress("FunctionName")
fun FileSelectionDialog(
    files: FileManagerService,
    trees: SafTreeScopeService,
    feature: FeatureFiles,
    selected: (FileScopePath) -> Unit,
    cancel: () -> Unit,
) {
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.files_choose_import), style = MaterialTheme.typography.titleLarge)
                    TextButton(cancel) { Text(stringResource(R.string.common_cancel)) }
                }
                FilesScreen(files, trees, feature, onPickFile = selected)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun FilesSelectionLayout(
    state: FilesScreenState,
    actions: FilesScreenActions,
    selected: (FileScopePath) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.homeOpen) {
            LazyColumn {
                items(
                    state.sources.filter { it.available && it.kind != FileSourceKind.ROOT },
                    key = { it.scopeId },
                ) { source ->
                    TextButton(
                        { state.openLocation(source.scopeId) },
                        Modifier.fillMaxWidth(),
                    ) { Text(source.displayName) }
                }
            }
        } else {
            if (!state.hasSelectedSource) {
                Text(stringResource(R.string.files_import_unavailable))
                TextButton({ state.goHome() }) { Text(stringResource(R.string.files_back)) }
                return@Column
            }
            TextButton({ state.goBack() }) { Text(stringResource(R.string.files_back)) }
            Text(state.currentSource.displayName + " / " + state.currentPath)
            OutlinedTextField(
                state.searchQuery,
                { state.searchQuery = it },
                label = { Text(stringResource(R.string.files_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.loadError != null) {
                Text(stringResource(R.string.files_read_directory_error))
                TextButton({ state.reloadTick++ }) { Text(stringResource(R.string.chat_retry)) }
            }
            if (state.listingTruncated) Text(stringResource(R.string.files_listing_partial))
            LazyColumn {
                items(state.visibleEntries, key = { it.relativePath }) { entry ->
                    FileRow(entry, false, selectionMode = false, onToggle = {}, onClick = {
                        if (entry.isDirectory) {
                            actions.onEntry(entry)
                        } else {
                            selected(FileScopePath(state.selectedScopeId, entry.relativePath))
                        }
                    })
                }
            }
        }
    }
}
