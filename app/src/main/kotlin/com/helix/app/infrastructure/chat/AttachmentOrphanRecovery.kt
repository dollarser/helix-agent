package com.helix.app.infrastructure.chat

import com.helix.core.workspace.AttachmentSnapshotPath
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Run before consumers start: no importer or normalizer may publish concurrently with this scan. */
internal class AttachmentOrphanRecovery(
    private val root: Path,
    private val referenceCount: (String) -> Int,
    private val onFailure: (IOException) -> Unit = { throw it },
) {
    fun recover(): Int {
        val store = WorkspaceArtifactStore(rootResolver = ScopeRootResolver { root })
        return listOf("input/attachments" to 2, "input/attachment-derived" to 3).sumOf { (relative, depth) ->
            candidates(root.resolve(relative), depth).count { file -> retire(store, file) }
        }
    }

    private fun candidates(
        directory: Path,
        depth: Int,
    ): List<Path> {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || hasSymbolicParent(directory)) return emptyList()
        return Files.walk(directory, depth).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .toList()
        }
    }

    private fun retire(
        store: WorkspaceArtifactStore,
        file: Path,
    ): Boolean {
        val ref = reference(file)
        if (ref == null || referenceCount(ref.toModelReference()) != 0) {
            return false
        }
        return try {
            store.ensureLayout("app")
            store.retireAttachmentSnapshot(ref)
            true
        } catch (failure: IOException) {
            onFailure(failure)
            false
        }
    }

    private fun reference(file: Path): FileScopePath? {
        val relative = root.relativize(file).toString()
        return try {
            FileScopePath("app", relative).takeIf {
                it.relativePath == relative && AttachmentSnapshotPath.contains(it) && !hasSymbolicParent(file)
            }
        } catch (_: IllegalArgumentException) {
            // Unknown filenames are not importer-owned evidence; retain them without retargeting.
            null
        }
    }

    private fun hasSymbolicParent(path: Path): Boolean =
        Files.isSymbolicLink(root) ||
            generateSequence(path) { it.parent }.takeWhile { it != root }.any(Files::isSymbolicLink)
}
