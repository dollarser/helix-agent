package com.helix.app.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import com.helix.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun FilesDirectoryEffects(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    with(actions) {
        with(state) {
            // ── Data loading (all file access is containment-enforced by the service; IO off the UI thread) ──
            LaunchedEffect(selectedScopeId, currentPath, sortKey, reloadTick, trashOpen) {
                if (trashOpen) return@LaunchedEffect
                loadError = null
                entries = emptyList()
                selected = emptySet()
                searchQuery = ""
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching { fileManager.list(selectedScopeId, currentPath, sortKey) }
                    }
                result.fold(
                    onSuccess = {
                        entries = it
                        selected = emptySet()
                    },
                    onFailure = { loadError = it.message ?: str(R.string.files_read_directory_error) },
                )
            }

            LaunchedEffect(trashOpen, selectedScopeId, reloadTick) {
                if (!trashOpen) return@LaunchedEffect
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching { fileManager.listTrash(selectedScopeId) }
                    }
                trashEntries = result.getOrDefault(emptyList())
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // Read failure is shown; cancellation still propagates.
internal fun FilesPreviewEffects(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    val file = state.openFile
    val scopeId = state.selectedScopeId
    LaunchedEffect(file?.relativePath, scopeId) {
        if (file == null || file.isDirectory) return@LaunchedEffect
        state.preview = FilePreviewState.Loading
        val loaded =
            withContext(Dispatchers.IO) {
                try {
                    val text = actions.fileManager.previewText(scopeId, file.relativePath)
                    val image =
                        if (text == null) {
                            val bytes = actions.fileManager.previewImageBytes(scopeId, file.relativePath)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } else {
                            null
                        }
                    FilePreviewState.Ready(text, image, actions.fileManager.fileInfo(scopeId, file.relativePath))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    FilePreviewState.Failed(failure.message ?: actions.str(R.string.files_read_directory_error))
                }
            }
        // IO completion may resume on a test/effect dispatcher. Publish on Android's UI dispatcher.
        withContext(Dispatchers.Main.immediate) {
            if (state.openFile?.relativePath == file.relativePath && state.selectedScopeId == scopeId) {
                state.preview = loaded
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun FilesScopeEffects(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    with(actions) {
        with(state) {
            // The conflict dialog's suggested "重命名" target (the next non-colliding sibling).
            LaunchedEffect(conflictTarget) {
                val target = conflictTarget ?: return@LaunchedEffect
                suggestedName =
                    withContext(Dispatchers.IO) { fileManager.nextAvailableName(selectedScopeId, target.second) }
            }

            // HXA-057: when the SAF panel opens, re-verify the live grants (a revoked / dead grant is
            // dropped) — the list never offers a scope the resolver cannot actually resolve.
            LaunchedEffect(safPanelOpen) {
                if (!safPanelOpen) return@LaunchedEffect
                safSources =
                    withContext(Dispatchers.IO) { runCatching { safTree.liveSources() }.getOrDefault(emptyList()) }
            }

            // HXA-058: when the export dialog opens — and each time the destination shape switches to
            // the authorized-tree mode — re-verify the live SAF grants (the export-to-tree destination
            // list never offers a scope the resolver cannot resolve; a WRITE re-verification still
            // happens at export time, fail closed).
            LaunchedEffect(exportOpen, exportMode) {
                if (!exportOpen || exportMode != ExportMode.TREE) return@LaunchedEffect
                exportSources =
                    withContext(Dispatchers.IO) { runCatching { safTree.liveSources() }.getOrDefault(emptyList()) }
            }
        }
    }
}
