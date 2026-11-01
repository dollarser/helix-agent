package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Explicit manual operations; roll back owned staging on provider failure, then propagate. */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class ManualFileOperations internal constructor(
    private val backend: (String) -> ManualFileBackend,
    private val writable: (String) -> Boolean,
    private val journal: ManualTransferJournal? = null,
) {
    private val tree = ManualFileTree()

    fun canWrite(scope: String): Boolean = writable(scope)

    internal fun syncBackend(scope: String): ManualFileBackend {
        check(canWrite(scope)) { "SYNC_PERMISSION_REVOKED" }
        return backend(scope)
    }

    /** No writes or child contents: opening/enumerating the selected directory is the actual read probe. */
    internal fun probeDirectory(
        scope: String,
        path: String,
    ) {
        val fs = backend(scope)
        require(fs.stat(path)?.directory == true) { "Workspace directory unavailable" }
        fs.children(path)
    }

    /** Explicit selection only; reads use the same live-authorized backend as ordinary browsing. */
    fun captureImport(
        scope: String,
        path: String,
        limit: Long,
        cancelled: () -> Boolean,
    ): ByteArray {
        val source = FileScopePath(scope, path)
        require(!source.isRoot)
        val fs = backend(scope)
        val before = fs.stat(source.relativePath) ?: throw java.io.FileNotFoundException("Import source unavailable")
        require(!before.directory) { "IMPORT_SOURCE_UNAVAILABLE" }
        require(before.size < 0 || before.size <= limit) { "CONNECTOR_TOO_LARGE" }
        val bytes = fs.read(source.relativePath).use { ImportFileCapture.read(it, limit, cancelled) }
        val after = fs.stat(source.relativePath)
        check(after == before && (before.size < 0 || bytes.size.toLong() == before.size)) { "IMPORT_SOURCE_CHANGED" }
        // A size alone is not a content identity, especially with an externally edited shared document.
        val verified = fs.read(source.relativePath).use { ImportFileCapture.read(it, limit, cancelled) }
        check(bytes.contentEquals(verified)) { "IMPORT_SOURCE_CHANGED" }
        return bytes
    }

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
    @Suppress(
        "LongMethod",
        "ThrowsCount",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "SwallowedException",
        "LongParameterList", // Four scoped coordinates plus explicit copy and revision boundaries.
    )
    @Synchronized
    fun transfer(
        sourceScope: String,
        sourcePath: String,
        targetScope: String,
        targetPath: String,
        move: Boolean,
        overwrite: Boolean,
        cancelled: () -> Boolean = { false },
        expected: TransferExpectation? = null,
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
        val from = backend(sourceScope).let { if (expected == null) it else boundedSyncBackend(it) }
        val to = backend(targetScope).let { if (expected == null) it else boundedSyncBackend(it) }
        to.validateMutation(target.relativePath)
        if (move) from.validateMutation(source.relativePath)
        requireNotNull(from.stat(source.relativePath)) { "Source is missing" }
        val existed = to.stat(target.relativePath) != null
        if (existed && !overwrite) throw FileAlreadyExistsException(target.relativePath)
        checkCancelled(cancelled)
        if (expected != null) {
            check(tree.fingerprint(from, sourcePath, cancelled) == expected.source) {
                "SYNC_SOURCE_CHANGED"
            }
        }
        if (expected != null) {
            check(
                (if (existed) tree.fingerprint(to, targetPath, cancelled) else "") == expected.target,
            ) { "SYNC_TARGET_CHANGED" }
        }
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
            if (expected != null) check(record.newHash == expected.source) { "SYNC_SOURCE_CHANGED" }
            if (expected != null) check(record.oldHash == expected.target) { "SYNC_TARGET_CHANGED" }
            journal?.save(record)
            check((to.stat(targetPath) != null) == existed) { "Destination changed before publication" }
            if (existed) {
                to.rename(target.relativePath, backup)
                backedUp = true
                check(
                    tree.fingerprint(to, backup, cancelled) == record.oldHash,
                ) { "Destination changed during publication" }
            }
            record = record.copy(phase = ManualTransferPhase.PUBLISHING)
            journal?.save(record)
            to.rename(temporary, target.relativePath)
            published = true
            record = record.copy(phase = ManualTransferPhase.PUBLISHED)
            journal?.save(record)
            check(tree.fingerprint(to, target.relativePath, cancelled) == record.newHash) {
                "Published destination changed; review required"
            }
            // Verify the source again before deleting: edits during a transfer must not be lost.
            if (move) {
                check(tree.sameTree(from, source.relativePath, to, target.relativePath, cancelled, 0)) {
                    "Source changed; destination copied, source retained"
                }
                record = record.copy(phase = ManualTransferPhase.DELETING_SOURCE)
                journal?.save(record)
                tree.deleteTree(from, source.relativePath, cancelled, 0)
            }
            if (backedUp) {
                check(tree.fingerprint(to, backup) == record.oldHash) { "Backup changed; review required" }
                tree.deleteTree(to, backup, { false }, 0)
            }
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

data class TransferExpectation(
    val source: String,
    val target: String,
)
