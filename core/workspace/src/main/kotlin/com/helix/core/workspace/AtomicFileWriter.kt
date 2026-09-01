package com.helix.core.workspace

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Atomic file writer (HXA-041): temporary write + fsync + replace.
 *
 * A file is only ever published by an atomic [java.nio.file.StandardCopyOption.ATOMIC_MOVE]
 * of a temp sibling into its final name, so a reader never observes a partial file and a crash
 * mid-write leaves the previous version (or nothing) intact. Before [publish] the temp file and
 * its containing directory are both fsync'ed (file data + the directory entry), which is what
 * actually makes the rename durable on a power loss.
 *
 * The temp file is created with a random, unique suffix in the *same* directory as the target
 * (a cross-directory move is not atomic). [cleanup] removes any temp a previous run abandoned.
 */
object AtomicFileWriter {
    /** A temp file the writer owns that may be left behind by a crash and reclaimed. */
    private const val TEMP_PREFIX = ".helix-tmp-"

    /**
     * Writes [bytes] to [target] atomically.
     *
     * @param expectedPreviousSha256 when non-null, the target must already exist with exactly
     *   this SHA-256 (前置 hash / optimistic concurrency). A mismatch — including the target
     *   missing — fails closed with [PreconditionHashMismatch] so a stale write can never clobber
     *   a file that changed underneath the caller.
     * @return the SHA-256 of the bytes as written (hex).
     * @throws IOException on I/O failure.
     * @throws PreconditionHashMismatch when [expectedPreviousSha256] does not match.
     */
    fun writeAtomic(
        target: Path,
        bytes: ByteArray,
        expectedPreviousSha256: String? = null,
    ): String {
        val directory = target.parent
        require(directory != null) { "target must have a parent directory" }
        require(Files.isDirectory(directory)) { "target parent must be a directory" }

        if (expectedPreviousSha256 != null) {
            val actual = if (Files.exists(target)) sha256Hex(target) else null
            if (actual != expectedPreviousSha256) {
                throw PreconditionHashMismatch(
                    expectedPreviousSha256,
                    actual ?: "missing",
                )
            }
        }

        val hash = sha256HexOf(bytes)
        val temp = Files.createTempFile(directory, TEMP_PREFIX, null)
        try {
            writeAndFsync(temp, bytes)
            val parent = temp.toRealPath().parent
            directoryFsync(parent ?: directory)
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            // Best-effort cleanup of the temp on any I/O failure; the real failure is rethrown.
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                // leave it; cleanup() reclaims orphaned temps later
            }
            throw e
        }
        return hash
    }

    /**
     * Deletes every abandoned `.{@see TEMP_PREFIX}` sibling in [directory] (orphan reclamation).
     * @return the number of temp files removed.
     */
    fun cleanup(directory: Path): Int {
        if (!Files.isDirectory(directory)) return 0
        var removed = 0
        // The stream predicate receives the resolved Path, not the file name, so compare the
        // final component explicitly — Path.startsWith(String) would match from the root and
        // never match a temp prefix in a nested directory.
        Files.newDirectoryStream(directory) { p -> p.fileName.toString().startsWith(TEMP_PREFIX) }.use { stream ->
            for (temp in stream) {
                if (Files.deleteIfExists(temp)) removed++
            }
        }
        return removed
    }

    /**
     * Recursively reclaims abandoned `.{@see TEMP_PREFIX}` files under [root] (and the root
     * itself). An interrupted atomic write leaves its temp in the *target's own* directory, which
     * can be a nested one (e.g. `work/a/b.txt` or `.helix/metadata.json`), so a per-leaf scan
     * would miss them. The walk never follows a symlinked directory, so an escaping link cannot
     * steer the cleanup out of the scope. @return the number of temp files removed.
     */
    fun cleanupRecursively(root: Path): Int {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) return 0
        var removed = cleanup(root)
        for (entry in Files.newDirectoryStream(root)) {
            if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                removed += cleanupRecursively(entry)
            }
        }
        return removed
    }

    /** Reads [source] fully, hashing on the fly. Fail-closed on any I/O error. */
    fun sha256Hex(source: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        FileChannel.open(source, StandardOpenOption.READ).use { channel ->
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                buffer.flip()
                while (buffer.hasRemaining()) {
                    digest.update(buffer)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256HexOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Writes all of [bytes] to [temp] and fsyncs the file channel before closing. */
    private fun writeAndFsync(
        temp: Path,
        bytes: ByteArray,
    ) {
        FileChannel
            .open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
    }

    /** fsyncs a directory so a rename/new entry is durable. */
    private fun directoryFsync(directory: Path) {
        FileChannel
            .open(
                directory,
                StandardOpenOption.READ,
            ).use { channel ->
                channel.force(true)
            }
    }
}

/**
 * The target file did not carry the expected SHA-256 before a guarded atomic write, so the write
 * was refused (前置 hash mismatch; fail closed, nothing was changed).
 *
 * [actualSha256] is either the observed hash or the literal `missing` when the file was absent.
 */
class PreconditionHashMismatch(
    val expected: String,
    val actualSha256: String,
) : RuntimeException(
        "precondition hash mismatch (expected $expected, actual $actualSha256)",
    ),
    WorkspaceFileError {
    override fun toString(): String = "precondition hash mismatch (expected $expected, actual $actualSha256)"
}

/** Marker for the fail-closed workspace file errors this layer produces (doc 13: bounded output). */
interface WorkspaceFileError
