package com.helix.app.ui

import androidx.compose.runtime.saveable.listSaver
import com.helix.app.files.FileManagerService

/** Restore navigation only. Grants, file contents and pending destructive actions are reread. */
internal fun filesNavigationSaver(files: FileManagerService) =
    listSaver<FilesScreenState, Any>(
        save = {
            listOf(
                it.selectedScopeId,
                it.currentPath,
                it.homeOpen,
                it.workspacesOpen,
                it.searchOpen,
                it.searchQuery,
                it.openFile?.relativePath.orEmpty(),
            )
        },
        restore = {
            FilesScreenState(files).apply {
                selectedScopeId = it[0] as String
                currentPath = it[1] as String
                homeOpen = it[2] as Boolean
                workspacesOpen = it[3] as Boolean
                searchOpen = it[4] as Boolean
                searchQuery = it[5] as String
                val previewPath = it[6] as String
                if (previewPath.isNotEmpty()) {
                    openFile =
                        FileManagerService.FileEntry(previewPath.substringAfterLast('/'), previewPath, false, 0, 0)
                }
            }
        },
    )
