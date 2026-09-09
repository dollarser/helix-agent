package com.helix.app.files

import com.helix.app.R
import com.helix.app.files.FileManagerService.FileOpResult
import com.helix.app.files.FileManagerService.TrashEntryView
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import java.io.FileNotFoundException
import java.nio.file.FileAlreadyExistsException

/** Workspace recycle-bin queries and explicit recovery/purge; not shared-storage deletion. */
internal class FileManagerTrash(
    private val store: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val directoryTrash: ManualWorkspaceTrash,
    private val strings: (Int, Array<out Any>) -> String,
) {
    private fun loc(id: Int): String = strings(id, emptyArray())

    private fun joinPath(
        dir: String,
        name: String,
    ): String = if (dir.isEmpty()) name else "$dir/$name"

    /** The current trash contents, newest storage order; empty when there is none. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed empty list
    fun listTrash(scopeId: String): List<TrashEntryView> {
        val trashDir = FileScopePath(scopeId, WorkspaceLayout.TRASH)
        return try {
            store
                .listDir(trashDir, MAX_LIST_ENTRIES)
                .entries
                .mapNotNull { entryName ->
                    val original = decodeTrashEntryName(entryName) ?: return@mapNotNull null
                    val s = store.stat(FileScopePath(scopeId, joinPath(WorkspaceLayout.TRASH, entryName)))
                    TrashEntryView(entryName, original, s.sizeBytes)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Restores a trash entry to its original path. [FileOpResult.Conflict] when the original path
     * is now occupied (the entry stays in the trash) — the user resolves it (skip/rename/overwrite).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun restore(
        scopeId: String,
        entryName: String,
    ): FileOpResult {
        val ref = FileScopePath(scopeId, joinPath(WorkspaceLayout.TRASH, entryName))
        return try {
            if (scopeId == workspaceScopeId && directoryTrash.isDirectory(ref.relativePath)) {
                val original = requireNotNull(decodeTrashEntryName(entryName))
                directoryTrash.restore(entryName, original)
                return FileOpResult.Ok(original, false)
            }
            val out = store.restoreFromTrash(ref)
            FileOpResult.Ok(out.restoredRelativePath, overwritten = false)
        } catch (e: FileAlreadyExistsException) {
            FileOpResult.Conflict
        } catch (e: FileNotFoundException) {
            FileOpResult.NotFound(loc(R.string.files_error_trash_entry_missing))
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_restore_failed))
        }
    }

    /** Permanently deletes ONE trash entry (the physical-empty half). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // I/O failure maps to a fail-closed FileOpResult
    fun purge(
        scopeId: String,
        entryName: String,
    ): FileOpResult {
        val ref = FileScopePath(scopeId, joinPath(WorkspaceLayout.TRASH, entryName))
        return try {
            if (scopeId == workspaceScopeId && directoryTrash.isDirectory(ref.relativePath)) {
                requireNotNull(decodeTrashEntryName(entryName))
                directoryTrash.purge(entryName)
                return FileOpResult.Ok(ref.relativePath, false)
            }
            val out = store.purgeTrashEntry(ref)
            FileOpResult.Ok(out.purgedRelativePath, overwritten = false)
        } catch (e: FileNotFoundException) {
            FileOpResult.NotFound(loc(R.string.files_error_trash_entry_missing))
        } catch (e: Exception) {
            FileOpResult.Error(e.message ?: loc(R.string.files_error_purge_failed))
        }
    }

    /** Permanently deletes every trash entry; @return the number of entries purged. */
    fun emptyTrash(scopeId: String): Int = listTrash(scopeId).count { purge(scopeId, it.entryName) is FileOpResult.Ok }

    // --- Trash-entry name decoding (the exact inverse of the store's `encodeTrashPath`) ---

    private fun decodeTrashEntryName(entryName: String): String? {
        val match = WorkspaceArtifactStore.TRASH_ENTRY_NAME.matchEntire(entryName) ?: return null
        return decodeTrashPath(match.groupValues[3])
    }

    private fun decodeTrashPath(encoded: String): String? {
        val out = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            val decoded =
                when {
                    i + 2 >= encoded.length -> null
                    else -> decodeEscape(encoded, i)
                } ?: return null
            out.append(decoded)
            i += 3
        }
        return out.toString()
    }

    private fun decodeEscape(
        encoded: String,
        at: Int,
    ): Char? =
        when (encoded.substring(at + 1, at + 3)) {
            "25" -> '%'
            "2F" -> '/'
            else -> null
        }

    private companion object {
        const val MAX_LIST_ENTRIES = 500
    }
}
