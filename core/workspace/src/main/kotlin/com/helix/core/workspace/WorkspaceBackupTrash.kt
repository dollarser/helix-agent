package com.helix.core.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Private, verified backup before destructive access. Pending receipts never replay a deletion. */
internal class WorkspaceBackupTrash(
    private val directory: Path,
    private val store: WorkspaceArtifactStore,
    private val delete: (FileScopePath) -> Unit,
    private val create: (FileScopePath) -> Unit,
) {
    // Once deletion starts, every failure retains the backup for review.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun move(path: FileScopePath): TrashEntry {
        require(WorkspaceLayout.regionOf(path.relativePath) != null)
        val info = store.stat(path)
        require(info.isRegularFile && info.sizeBytes in 0..DocumentWorkspaceOperations.MAX_BYTES.toLong()) {
            "File cannot be backed up within the recoverable deletion limit"
        }
        val bytes = store.readAll(path)
        require(bytes.size <= DocumentWorkspaceOperations.MAX_BYTES)
        Files.createDirectories(directory)
        WorkspaceQuota.ensureRoom(directory, bytes.size.toLong(), WorkspaceQuotaPolicy.default.maxWorkspaceBytes)
        val name =
            "${System.currentTimeMillis()}-${UUID.randomUUID().toString().replace("-", "").take(8)}__" +
                path.relativePath.replace("%", "%25").replace("/", "%2F")
        val backup = directory.resolve(name)
        val hash = AtomicFileWriter.writeAtomic(backup, bytes)
        check(AtomicFileWriter.sha256Hex(backup) == hash)
        // Durable PREPARED proves backup, not deletion. On reopen, keep both sides for review.
        phase(name, "PREPARED", hash)
        if (DocumentWorkspaceOperations.sha256(store.readAll(path)) != hash) {
            throw PreconditionHashMismatch(hash, "changed-before-delete")
        }
        try {
            delete(path)
            if (store.stat(path).exists) throw IOException("Deletion not verified")
            phase(name, "DELETED", hash)
        } catch (failure: Exception) {
            throw WorkspaceMutationUncertain(failure)
        }
        return TrashEntry(path.relativePath, name, bytes.size.toLong(), hash)
    }

    fun restore(ref: FileScopePath): TrashRestoreOutcome {
        val name = entryName(ref)
        val target = FileScopePath(ref.scopeId, decode(name.substringAfter("__")))
        require(WorkspaceLayout.regionOf(target.relativePath) != null)
        if (store.stat(target).exists) throw java.nio.file.FileAlreadyExistsException(target.toModelReference())
        val backup = directory.resolve(name)
        require(Files.size(backup) <= DocumentWorkspaceOperations.MAX_BYTES)
        val bytes = Files.readAllBytes(backup)
        val hash = receipt(name).second
        require(DocumentWorkspaceOperations.sha256(bytes) == hash) { "Backup integrity mismatch; retained for review" }
        phase(name, "RESTORING", hash)
        create(target)
        store.writeArtifact(
            target,
            bytes,
            requireNotNull(WorkspaceLayout.regionOf(target.relativePath)),
            DocumentWorkspaceOperations.sha256(byteArrayOf()),
        )
        if (DocumentWorkspaceOperations.sha256(store.readAll(target)) != hash) {
            throw IOException("Restoration not verified; backup retained")
        }
        phase(name, "RESTORED", hash)
        Files.delete(backup)
        Files.deleteIfExists(receiptFile(name))
        return TrashRestoreOutcome(target.relativePath, store.usageBytes(ref.scopeId))
    }

    fun purge(ref: FileScopePath): PurgeOutcome {
        val name = entryName(ref)
        val receipt = receiptFile(name)
        require(Files.exists(receipt) && receipt(name).first in setOf("DELETED", "RESTORED")) {
            "Unsettled backup requires review, not purge"
        }
        Files.delete(directory.resolve(name))
        Files.deleteIfExists(receipt)
        return PurgeOutcome(ref.relativePath, store.usageBytes(ref.scopeId))
    }

    private fun phase(
        name: String,
        phase: String,
        hash: String,
    ) {
        Files.createDirectories(receiptFile(name).parent)
        AtomicFileWriter.writeAtomic(receiptFile(name), "$phase\n$hash".toByteArray())
    }

    private fun receiptFile(name: String): Path = directory.resolveSibling("trash-receipts").resolve(name)

    private fun receipt(name: String): Pair<String, String> {
        val file = receiptFile(name)
        require(Files.size(file) in 1..128) { "Invalid backup receipt" }
        val fields = String(Files.readAllBytes(file)).split('\n')
        require(fields.size == 2 && fields[1].matches(Regex("[a-f0-9]{64}"))) { "Invalid backup receipt" }
        return fields[0] to fields[1]
    }

    private fun entryName(ref: FileScopePath): String {
        require(
            ref.parent.relativePath == WorkspaceLayout.TRASH &&
                WorkspaceArtifactStore.TRASH_ENTRY_NAME.matches(ref.name),
        )
        return ref.name
    }

    private fun decode(encoded: String): String {
        require(!Regex("%(?!25|2F)").containsMatchIn(encoded)) { "Malformed backup reference" }
        return encoded.replace("%2F", "/").replace("%25", "%")
    }
}
