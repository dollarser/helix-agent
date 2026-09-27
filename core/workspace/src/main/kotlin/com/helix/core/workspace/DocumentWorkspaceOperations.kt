package com.helix.core.workspace

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Document operations stay in place. Version checks are optimistic, never advertised as atomic CAS. */
internal class DocumentWorkspaceOperations(
    private val backend: WorkspaceFileBackend,
) {
    fun stat(path: FileScopePath): StatInfo {
        val info = backend.stat(path.relativePath) ?: return StatInfo(false, -1, false, false, false, -1)
        return StatInfo(true, info.size, info.directory, !info.directory, false, -1)
    }

    fun readAll(path: FileScopePath): ByteArray =
        backend.read(path.relativePath).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Provider read made no progress")
                if (output.size() + count > MAX_BYTES) throw IOException("Document exceeds bounded operation size")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    fun readWindow(
        path: FileScopePath,
        offset: Long,
        maxBytes: Long,
    ): ReadWindow {
        val info = backend.stat(path.relativePath) ?: throw java.io.FileNotFoundException("Document unavailable")
        require(!info.directory && info.size >= 0) { "Provider does not expose a readable file size" }
        return backend.read(path.relativePath).use { ReadWindow.read(it, info.size, offset, maxBytes) }
    }

    fun probe(path: FileScopePath): ContentProbe.Result {
        val info = backend.stat(path.relativePath) ?: return ContentProbe.probeBytes(byteArrayOf(), -1)
        require(!info.directory)
        val bytes =
            backend.read(path.relativePath).use { input ->
                val buffer = ByteArray(ContentProbe.SAMPLE_BYTES)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) throw IOException("Provider read made no progress")
                    count += read
                }
                buffer.copyOf(count)
            }
        return ContentProbe.probeBytes(bytes, info.size)
    }

    fun list(
        path: FileScopePath,
        maxEntries: Int,
    ): ListResult {
        require(maxEntries >= 0)
        require(backend.stat(path.relativePath)?.directory == true)
        val entries = backend.children(path.relativePath).sorted()
        return ListResult(entries.take(maxEntries), entries.size > maxEntries)
    }

    @Suppress("TooGenericExceptionCaught") // Creation may have happened even when the provider reports failure.
    fun mkdir(path: FileScopePath) {
        backend.validateMutation(path.relativePath)
        if (backend.stat(path.relativePath) != null) throw java.nio.file.FileAlreadyExistsException(path.relativePath)
        try {
            backend.create(path.relativePath, true)
            check(backend.stat(path.relativePath)?.directory == true) { "Directory creation not verified" }
        } catch (failure: Exception) {
            throw WorkspaceMutationUncertain(failure)
        }
    }

    @Suppress("ReturnCount", "NestedBlockDepth") // Bounded traversal stops at either independent resource limit.
    fun search(
        base: FileScopePath,
        needle: String,
        maxResults: Int,
        maxScan: Int,
    ): SearchResult {
        val pending = java.util.ArrayDeque<FileScopePath>()
        pending.add(base)
        val matches = mutableListOf<FileScopePath>()
        var scanned = 0
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            for (name in backend.children(directory.relativePath).sorted()) {
                if (name == WorkspaceLayout.HELIX) continue
                if (++scanned > maxScan) return SearchResult(matches, true)
                val child =
                    FileScopePath(
                        base.scopeId,
                        listOf(directory.relativePath, name).filter(String::isNotEmpty).joinToString("/"),
                    )
                if (name.contains(needle, ignoreCase = true)) {
                    if (matches.size >= maxResults) return SearchResult(matches, true)
                    matches += child
                }
                if (backend.stat(child.relativePath)?.directory == true) pending.add(child)
            }
        }
        return SearchResult(matches, false)
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // Preserve every provider failure as mutation uncertainty.
    fun write(
        path: FileScopePath,
        bytes: ByteArray,
        expectedHash: String?,
        turnId: String?,
    ): WriteOutcome {
        require(bytes.size <= MAX_BYTES) { "Document exceeds bounded write size" }
        backend.validateMutation(path.relativePath)
        val exists = backend.stat(path.relativePath) != null
        if (expectedHash != null && (!exists || sha256(readAll(path)) != expectedHash)) {
            throw PreconditionHashMismatch(expectedHash, "missing-or-changed")
        }
        // After create/open, any error can leave a partial effect. Convert it to I/O uncertainty;
        // the existing write/edit executor reports requiresReview and never claims a rollback.
        try {
            if (!exists) backend.create(path.relativePath, false)
            backend.write(path.relativePath).use { it.write(bytes) }
            val hash = sha256(bytes)
            if (sha256(readAll(path)) != hash) throw IOException("Document write not verified")
            val probe =
                ContentProbe.probeBytes(
                    bytes.take(ContentProbe.SAMPLE_BYTES).toByteArray(),
                    bytes.size.toLong(),
                )
            return WriteOutcome(
                WorkspaceArtifactStore.ArtifactRecord(
                    "art_${UUID.randomUUID()}",
                    path.scopeId,
                    path.relativePath,
                    probe.mimeType,
                    bytes.size.toLong(),
                    hash,
                    turnId,
                ),
                probe,
                -1, // An external tree is not the app's private quota; aggregate usage is unknown.
            )
        } catch (failure: Exception) {
            throw WorkspaceMutationUncertain(failure)
        }
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}
