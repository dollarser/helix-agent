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
@Suppress("FunctionName")
internal fun FilesPreviewEffects(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    with(actions) {
        with(state) {
            // The open file's preview + metadata (text/image first, then the bounded info incl. SHA-256).
            LaunchedEffect(openFile?.relativePath, selectedScopeId) {
                val file = openFile ?: return@LaunchedEffect
                previewText = null
                previewImage = null
                fileInfo = null
                if (file.isDirectory) return@LaunchedEffect
                val loaded =
                    withContext(Dispatchers.IO) {
                        val text = fileManager.previewText(selectedScopeId, file.relativePath)
                        val image =
                            if (text == null) {
                                val bytes = fileManager.previewImageBytes(selectedScopeId, file.relativePath)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } else {
                                null
                            }
                        Triple(text, image, fileManager.fileInfo(selectedScopeId, file.relativePath))
                    }
                // Publish the completed preview and metadata together on the composition dispatcher.
                previewText = loaded.first
                previewImage = loaded.second
                fileInfo = loaded.third
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
