package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Explicit user operations, isolated from Agent dispatch and scopes. */
@Suppress("TooGenericExceptionCaught") // Roll back owned staging on any provider failure, then propagate.
class ManualFileOperations internal constructor(
    private val backend: (String) -> ManualFileBackend,
    private val writable: (String) -> Boolean,
    private val journal: ManualTransferJournal? = null,
) {
    private val tree = ManualFileTree()

    fun canWrite(scope: String): Boolean = writable(scope)

    fun exists(
        scope: String,
        path: String,
    ): Boolean = backend(scope).stat(path) != null

    fun mkdir(
        scope: String,
        parent: String,
        name: String,
    ) {
        check(canWrite(scope)) { "Storage is read-only or permission was revoked" }
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != "..")
        val path = join(parent, name)
        val fs = backend(scope)
        if (fs.stat(path) != null) throw FileAlreadyExistsException(path)
        fs.create(path, true)
    }

    /** Streams into an owned sibling; replacement is held as a backup until publication succeeds. */
    @Suppress("LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "NestedBlockDepth", "SwallowedException")
    @Synchronized
    fun transfer(
        sourceScope: String,
        sourcePath: String,
        targetScope: String,
        targetPath: String,
        move: Boolean,
        overwrite: Boolean,
        cancelled: () -> Boolean = { false },
    ): Boolean {
        check(
            canWrite(targetScope) && (!move || canWrite(sourceScope)),
        ) { "Storage is read-only or permission was revoked" }
        val source = FileScopePath(sourceScope, sourcePath)
        val target = FileScopePath(targetScope, targetPath)
        require(!source.isRoot && !target.isRoot) { "Choose a file or folder, not the storage root" }
        if (sourceScope == targetScope) {
            require(source != target && !overlaps(source.relativePath, target.relativePath)) {
                "A folder cannot be copied or moved into itself"
            }
        }
        val from = backend(sourceScope)
        val to = backend(targetScope)
        to.validateMutation(target.relativePath)
        if (move) from.validateMutation(source.relativePath)
        requireNotNull(from.stat(source.relativePath)) { "Source is missing" }
        val existed = to.stat(target.relativePath) != null
        if (existed && !overwrite) throw FileAlreadyExistsException(target.relativePath)
        checkCancelled(cancelled)
        val sameParent = sourceScope == targetScope && source.parent == target.parent
        val fastRename = move && !existed && sameParent
        if (journal == null && fastRename) {
            from.rename(source.relativePath, target.relativePath)
            return false
        }
        var record =
            ManualTransferRecord(UUID.randomUUID().toString(), sourceScope, sourcePath, targetScope, targetPath, move)
        val temporary = record.temporary
        val backup = record.backup
        journal?.save(record)
        var backedUp = false
        var published = false
        try {
            tree.copyTree(from, source.relativePath, to, temporary, cancelled, 0)
            check(tree.sameTree(from, source.relativePath, to, temporary, cancelled, 0)) {
                "Source changed during copy; original retained"
            }
            checkCancelled(cancelled)
            record =
                record.copy(
                    phase = ManualTransferPhase.PREPARED,
                    newHash = tree.fingerprint(to, temporary, cancelled),
                    oldHash = if (existed) tree.fingerprint(to, target.relativePath, cancelled) else "",
                )
            journal?.save(record)
            if (existed) {
                to.rename(target.relativePath, backup)
                backedUp = true
            }
            record = record.copy(phase = ManualTransferPhase.PUBLISHING)
            journal?.save(record)
            to.rename(temporary, target.relativePath)
            published = true
            record = record.copy(phase = ManualTransferPhase.PUBLISHED)
            journal?.save(record)
            // Verify the source again before deleting: edits during a transfer must not be lost.
            if (move) {
                check(tree.sameTree(from, source.relativePath, to, target.relativePath, cancelled, 0)) {
                    "Source changed; destination copied, source retained"
                }
                record = record.copy(phase = ManualTransferPhase.DELETING_SOURCE)
                journal?.save(record)
                tree.deleteTree(from, source.relativePath, cancelled, 0)
            }
            if (backedUp) tree.deleteTree(to, backup, { false }, 0)
            journal?.remove(record.id)
            return existed
        } catch (failure: Exception) {
            if (!published) {
                try {
                    if (record.phase >= ManualTransferPhase.PUBLISHING &&
                        to.stat(temporary) == null && to.stat(target.relativePath) != null
                    ) {
                        error("Publication may have completed; recover the recorded operation")
                    }
                    if (to.stat(temporary) != null) tree.deleteTree(to, temporary, { false }, 0)
                    if (to.stat(backup) != null &&
                        to.stat(target.relativePath) == null
                    ) {
                        to.rename(backup, target.relativePath)
                    }
                } catch (cleanup: Exception) {
                    throw IllegalStateException(
                        "Recovery needed: $temporary; $backup",
                        failure.apply { addSuppressed(cleanup) },
                    )
                }
            } else {
                throw IllegalStateException(
                    "Destination exists: ${target.relativePath}. Check source and backup: $backup",
                    failure,
                )
            }
            journal?.remove(record.id)
            throw failure
        }
    }

    @Synchronized
    fun pendingTransfers(): List<FileTransferRecovery> =
        journal?.let { ManualTransferRecovery(it, backend, writable).pending() }.orEmpty()

    @Synchronized
    fun recoverTransfer(id: String): Boolean =
        ManualTransferRecovery(requireNotNull(journal), backend, writable).recover(id)

    fun delete(
        scope: String,
        path: String,
        cancelled: () -> Boolean = { false },
    ) {
        check(canWrite(scope)) { "Storage is read-only or permission was revoked" }
        require(!FileScopePath(scope, path).isRoot) { "The storage root cannot be deleted" }
        val fs = backend(scope)
        fs.validateMutation(path)
        tree.deleteTree(fs, path, cancelled, 0)
    }

    private fun overlaps(
        left: String,
        right: String,
    ): Boolean = left.startsWith("$right/") || right.startsWith("$left/")

    private fun checkCancelled(cancel: () -> Boolean) {
        if (cancel()) throw CancellationException("File operation cancelled")
    }

    companion object {
        internal fun join(
            parent: String,
            name: String,
        ): String = if (parent.isEmpty()) name else "$parent/$name"
    }
}
