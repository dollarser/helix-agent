package com.helix.core.workspace

import com.helix.core.workspace.WorkspaceArtifactStore.Companion.TRASH_ENTRY_NAME
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class WorkspaceTrashOperations(
    private val resolve: (String) -> Path,
    private val resolveContained: (FileScopePath, Path) -> Path,
) {
    /**
     * Moves the regular file at [path] into the scope's `.helix/trash/` (HXA-043 `files.delete`)
     * as a single rename: the file's bytes and size are unchanged, the scope's aggregate usage
     * is unchanged (the trash lives inside the scope), and the original path is gone.
     *
     * The trash entry name is `<epochMillis>-<8-hex>__<encoded original relative path>`: the
     * encoded path is reversible (only `%` and `/` are escaped), so [restoreFromTrash] can find
     * the original location without any sidecar metadata, and the timestamp+id prefix makes a
     * name collision effectively impossible — a collision is still re-drawn, never clobbered.
     * @throws FileNotFoundException when [path] does not exist or is not a regular file.
     */
    fun moveToTrash(path: FileScopePath): TrashEntry {
        val root = resolve(path.scopeId)
        val source = resolveContained(path, root)
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw FileNotFoundException("not a regular file: ${path.toModelReference()}")
        }
        val size = Files.size(source)
        val sha = AtomicFileWriter.sha256Hex(source)
        val entryDir = PathResolution.join(root, WorkspaceLayout.TRASH)
        val entryName = uniqueTrashEntryName(entryDir, path.relativePath)
        Files.move(source, entryDir.resolve(entryName), StandardCopyOption.ATOMIC_MOVE)
        return TrashEntry(path.relativePath, entryName, size, sha)
    }

    /**
     * Restores a trash entry to its ORIGINAL relative path (HXA-043; the counterpart to
     * [moveToTrash], deliberately a SEPARATE operation from [purgeTrashEntry]). Fails closed
     * when the original path is currently occupied (the entry stays in the trash) or when the
     * referenced path is not a well-formed trash entry.
     * @throws FileNotFoundException when the trash entry does not exist.
     * @throws FileAlreadyExistsException when the original path is occupied.
     * @throws IllegalArgumentException when the reference is not a trash entry.
     */
    fun restoreFromTrash(trashRef: FileScopePath): TrashRestoreOutcome {
        require(isTrashReference(trashRef.relativePath)) { "path is not a trash entry" }
        val root = resolve(trashRef.scopeId)
        val entry = resolveContained(trashRef, root)
        if (!Files.exists(entry) || !Files.isRegularFile(entry)) {
            throw FileNotFoundException("trash entry not found: ${trashRef.toModelReference()}")
        }
        val originalRel = decodeTrashEntryName(entry.fileName.toString())
        val original = resolveContained(FileScopePath(trashRef.scopeId, originalRel), root)
        if (Files.exists(original)) {
            throw java.nio.file.FileAlreadyExistsException("original location is occupied: $originalRel")
        }
        original.parent?.let { Files.createDirectories(it) }
        Files.move(entry, original, StandardCopyOption.ATOMIC_MOVE)
        return TrashRestoreOutcome(originalRel, WorkspaceQuota.usageBytes(root))
    }

    /**
     * Permanently deletes ONE trash entry (HXA-043) — the physical-empty half, deliberately
     * separate from [restoreFromTrash]. Only well-formed trash entries under `.helix/trash/`
     * are purgable; anything else is refused, so this can never reach a user-region file.
     * @throws FileNotFoundException when the trash entry does not exist.
     * @throws IllegalArgumentException when the reference is not a trash entry.
     */
    fun purgeTrashEntry(trashRef: FileScopePath): PurgeOutcome {
        require(isTrashReference(trashRef.relativePath)) { "path is not a trash entry" }
        val root = resolve(trashRef.scopeId)
        val entry = resolveContained(trashRef, root)
        if (!Files.exists(entry) || !Files.isRegularFile(entry)) {
            throw FileNotFoundException("trash entry not found: ${trashRef.toModelReference()}")
        }
        Files.delete(entry)
        return PurgeOutcome(trashRef.relativePath, WorkspaceQuota.usageBytes(root))
    }

    private fun uniqueTrashEntryName(
        entryDir: Path,
        originalRelativePath: String,
    ): String {
        repeat(4) {
            val name =
                "${System.currentTimeMillis()}-${UUID.randomUUID().toString().replace("-", "").take(8)}__" +
                    encodeTrashPath(originalRelativePath)
            if (!Files.exists(entryDir.resolve(name))) return name
        }
        error("unable to allocate a unique trash entry name")
    }

    /** Reversible encoding for a trash entry name: escapes `%` then `/` (the layout separator). */
    private fun encodeTrashPath(relativePath: String): String = relativePath.replace("%", "%25").replace("/", "%2F")

    /** The exact inverse of [encodeTrashPath]; an unrecognized escape is a malformed entry. */
    private fun decodeTrashPath(encoded: String): String {
        val out = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%') {
                require(i + 2 < encoded.length) { "malformed trash entry name" }
                val escape = encoded.substring(i + 1, i + 3)
                require(escape == "25" || escape == "2F") { "malformed trash entry name" }
                out.append(if (escape == "25") '%' else '/')
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Parses a trash entry name back to its original relative path. */
    private fun decodeTrashEntryName(entryName: String): String {
        val match =
            TRASH_ENTRY_NAME.matchEntire(entryName)
                ?: throw IllegalArgumentException("malformed trash entry name")
        return decodeTrashPath(match.groupValues[3])
    }

    /** True for a path pointing directly at an entry inside `.helix/trash/`. */
    private fun isTrashReference(relativePath: String): Boolean {
        if (!relativePath.startsWith(WorkspaceLayout.TRASH + "/")) return false
        val name = relativePath.removePrefix(WorkspaceLayout.TRASH + "/")
        return name.isNotEmpty() && !name.contains('/') && TRASH_ENTRY_NAME.matches(name)
    }
}
