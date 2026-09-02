package com.helix.core.workspace

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
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
 * mid-write leaves the previous version (or nothing) intact. The durability order is: file data
 * fsync, the atomic rename, THEN the directory fsync — the directory entry made durable is the
 * TARGET's (a directory fsync before the rename would only pin the temp's entry, which the
 * rename removes). The post-rename directory fsync is best-effort: by then the publish already
 * happened, so its failure (some filesystems answer directory fsync with ENOSYS) degrades
 * power-loss durability only — reporting it as a failure would claim "not performed" for a
 * write that WAS performed.
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
            val dirToSync = temp.toRealPath().parent ?: directory
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            bestEffortDirectoryFsync(dirToSync)
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
     * Atomically publishes the stream that [sourceInto] writes into [target]: a temp sibling in
     * the same directory, then file fsync, the atomic rename, then the directory fsync — the
     * same durability story as [writeAtomic], but for content that must not sit in memory (SAF
     * imports, HXA-044).
     *
     * [sourceInto] receives the open temp stream and copies into it in chunks, which is where a
     * caller enforces per-chunk cancel checks and a hard byte cap by throwing [AbandonedWrite].
     * The stream is owned by this writer: [sourceInto] must write into it but NOT close it —
     * the writer performs the fsync, close, and the atomic rename.
     * On ANY failure — including an [AbandonedWrite] from [sourceInto] — the temp file is deleted
     * and nothing is published; the failure is rethrown to the caller.
     *
     * @throws IOException on I/O failure.
     * @throws AbandonedWrite when [sourceInto] abandoned the write.
     */
    fun writeAtomicStream(
        target: Path,
        sourceInto: (OutputStream) -> Unit,
    ) {
        val directory = target.parent
        require(directory != null) { "target must have a parent directory" }
        require(Files.isDirectory(directory)) { "target parent must be a directory" }

        val temp = Files.createTempFile(directory, TEMP_PREFIX, null)
        try {
            FileChannel
                .open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .use { channel ->
                    sourceInto(Channels.newOutputStream(channel))
                    channel.force(true)
                }
            val dirToSync = temp.toRealPath().parent ?: directory
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            bestEffortDirectoryFsync(dirToSync)
        } catch (e: AbandonedWrite) {
            // Best-effort cleanup of the temp; the caller's abandonment is rethrown as-is so
            // the pipeline can distinguish cancel from limit from I/O failure.
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                // leave it; cleanup() reclaims orphaned temps later
            }
            throw e
        } catch (e: IOException) {
            // Best-effort cleanup of the temp on any I/O failure; the real failure is rethrown.
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                // leave it; cleanup() reclaims orphaned temps later
            }
            throw e
        }
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
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                if (Files.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                    removed += cleanupRecursively(entry)
                }
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

    /**
     * fsyncs the directory AFTER the rename, best-effort: the publish already happened, so a
     * failure degrades power-loss durability only and is swallowed (some filesystems answer
     * directory fsync with ENOSYS) — rethrowing it would report a performed write as failed.
     */
    @Suppress("SwallowedException") // post-rename fsync failure: publish succeeded, durability degrades
    private fun bestEffortDirectoryFsync(directory: Path) {
        try {
            directoryFsync(directory)
        } catch (_: IOException) {
            // non-fatal: the rename sits in the page cache; only crash durability is degraded
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

/**
 * A streaming write ([AtomicFileWriter.writeAtomicStream]) was abandoned mid-stream by caller
 * policy — the temp file is deleted, nothing was published. It is a RuntimeException (the JVM
 * `IOException` is final) that the writer's cleanup path treats explicitly, like any other
 * failure: temp removed, failure rethrown.
 */
open class AbandonedWrite(
    message: String,
) : RuntimeException(message) {
    /** The caller's cancel signal fired mid-stream. */
    class Cancelled : AbandonedWrite("write abandoned: cancelled")

    /** The hard byte cap was exceeded mid-stream (e.g. a provider under-reporting its size). */
    class LimitExceeded : AbandonedWrite("write abandoned: byte limit exceeded")
}

/** Marker for the fail-closed workspace file errors this layer produces (doc 13: bounded output). */
interface WorkspaceFileError
