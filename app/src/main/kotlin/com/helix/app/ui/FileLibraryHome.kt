package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.files.FileLibrary
import com.helix.app.files.FileManagerService.FileEntry
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooGenericExceptionCaught")
internal fun FilesScreenActions.updateLibrary(
    onFailure: () -> Unit = {},
    action: FileLibrary.() -> Unit,
) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) { fileManager.library.action() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.status = str(R.string.files_library_failed)
            onFailure()
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun FileFavoriteAction(
    path: FileScopePath,
    directory: Boolean,
    actions: FilesScreenActions,
) {
    val library by actions.fileManager.library.state
        .collectAsStateWithLifecycle()
    LaunchedEffect(actions.fileManager) { actions.updateLibrary { load() } }
    var failed by remember(path) { mutableStateOf(false) }
    val favorite = library.favorites.any { it.reference == path.toModelReference() }
    TextButton(
        {
            failed = false
            actions.updateLibrary(onFailure = { failed = true }) { toggleFavorite(path, directory) }
        },
        modifier = Modifier.testTag(if (directory) "files-favorite-directory" else "files-favorite-file"),
    ) {
        Text(stringResource(if (favorite) R.string.files_unfavorite else R.string.files_favorite))
    }
    if (failed) Text(stringResource(R.string.files_library_failed))
}

@Composable
@Suppress("FunctionName")
internal fun FileLibraryHome(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    val library by actions.fileManager.library.state
        .collectAsStateWithLifecycle()
    LaunchedEffect(actions.fileManager) { actions.updateLibrary { load() } }
    FileLibrarySection(R.string.files_favorites, library.favorites, state, actions)
    FileLibrarySection(R.string.files_recent_opened, library.recent, state, actions)
}

@Composable
@Suppress("FunctionName")
private fun FileLibrarySection(
    title: Int,
    entries: List<FileLibrary.Entry>,
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.files_library_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        (if (expanded) entries else entries.take(4)).forEach { entry ->
            val path = entry.path
            val source = state.sources.firstOrNull { it.scopeId == path.scopeId }
            Row(Modifier.fillMaxWidth()) {
                TextButton({ actions.openLibraryEntry(entry) }, modifier = Modifier.weight(1f)) {
                    Column {
                        Text(if (path.isRoot) source?.displayName ?: path.name else path.name)
                        Text(
                            (source?.displayName ?: stringResource(R.string.workspace_unavailable)) + " / " +
                                path.parent.relativePath,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton({ actions.updateLibrary { forget(entry) } }) {
                    Text(stringResource(R.string.files_library_forget))
                }
            }
        }
        if (entries.size > 4) {
            TextButton({ expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.files_library_less else R.string.files_library_more))
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught") // An unavailable reference remains removable; never retarget it.
private fun FilesScreenActions.openLibraryEntry(entry: FileLibrary.Entry) {
    val path = entry.path
    scope.launch {
        try {
            val sources = withContext(Dispatchers.IO) { fileManager.sources() }
            check(sources.any { it.scopeId == path.scopeId && it.available })
            val file =
                withContext(Dispatchers.IO) {
                    if (entry.directory) {
                        fileManager.listing(path.scopeId, path.relativePath)
                        null
                    } else {
                        val info = fileManager.fileInfo(path.scopeId, path.relativePath)
                        FileEntry(path.name, path.relativePath, false, info.sizeBytes, info.mtimeEpochMillis)
                    }
                }
            state.replaceSources(sources)
            state.openLocation(path.scopeId)
            state.currentPath = if (entry.directory) path.relativePath else path.parent.relativePath
            state.openFile = file
            if (entry.directory) updateLibrary { visited(path, true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.status = str(R.string.files_library_unavailable)
        }
    }
}
