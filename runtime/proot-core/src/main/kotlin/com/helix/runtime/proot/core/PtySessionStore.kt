package com.helix.runtime.proot.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runtime-process-only journal. Atomic compare-and-set across wrappers in that process; callers
 * must not write from the app process. Corruption is an error, never an absent/free session.
 * No eviction of unreconciled evidence, no shell restart, and no execution-admission mutation.
 */
class PtySessionStore(
    private val directory: File,
) {
    fun read(sessionId: String): PtySessionRecord? = synchronized(lock) { readLocked(sessionId) }

    /** Explicit recovery/listing only; this does not bind or start the Runtime. */
    fun records(): List<PtySessionRecord> =
        synchronized(lock) {
            if (!directory.exists()) return@synchronized emptyList()
            val files = checkNotNull(directory.listFiles()) { "PTY journal unavailable" }
            check(files.size <= MAX_ENTRIES * 2) { "PTY journal directory limit" }
            files.filter { it.name.endsWith(SUFFIX) }.map {
                checkNotNull(readLocked(it.name.removeSuffix(SUFFIX)))
            }
        }

    fun compareAndSet(
        expected: PtySessionRecord?,
        replacement: PtySessionRecord,
    ): Boolean =
        synchronized(lock) {
            val id = replacement.origin.sessionId
            require(expected == null || expected.origin == replacement.origin) { "PTY origin cannot change" }
            if (readLocked(id) != expected) return@synchronized false
            if (expected != null) validateUpdate(expected, replacement)
            if (expected == null) {
                require(replacement == PtySessionRecord(replacement.origin)) { "PTY must start with durable intent" }
            }
            check(directory.isDirectory || directory.mkdirs()) { "PTY journal unavailable" }
            val bytes = PtySessionRecordCodec.encode(replacement)
            val temporary = File.createTempFile("pty-", ".pending", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    file(id).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            } finally {
                temporary.delete()
            }
        }

    /** Only an exact already-acknowledged record may be removed; unknown/unreconciled evidence stays. */
    fun removeReconciled(expected: PtySessionRecord): Boolean =
        synchronized(lock) {
            require(expected.reconciled)
            if (readLocked(expected.origin.sessionId) != expected) return@synchronized false
            check(
                PtyStateFiles.removeMetadata(
                    directory,
                    file(expected.origin.sessionId).name,
                    PtySessionRecordCodec.encode(expected),
                ),
            ) {
                "PTY journal removal failed"
            }
            true
        }

    private fun readLocked(id: String): PtySessionRecord? {
        check(!directory.exists() || directory.isDirectory) { "PTY journal unavailable" }
        val primary = file(id)
        val reclaim = File(directory, primary.name + ".reclaim")
        val file = if (primary.exists()) primary else reclaim
        if (!file.exists()) return null
        check(
            java.nio.file.Files
                .isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                file.length() in 1..PtySessionRecordCodec.MAX_BYTES.toLong(),
        ) { "Invalid PTY journal size" }
        // Recheck the read bound, not just the earlier stat, before allocating/decoding.
        val bytes =
            java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(PtySessionRecordCodec.MAX_BYTES + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
        return PtySessionRecordCodec
            .decode(
                bytes,
            ).also {
                check(it.origin.sessionId == id) { "Wrong PTY journal identity" }
                if (file == reclaim) check(it.reconciled && it.stopProof != null) { "Unproven reclamation receipt" }
            }
    }

    private fun validateUpdate(
        old: PtySessionRecord,
        next: PtySessionRecord,
    ) {
        if (old.stopProof != null) {
            require(next == old || next == old.acknowledge()) { "PTY stopped facts are immutable" }
        } else {
            require(!next.reconciled) { "Persist stop proof before acknowledgement" }
            require(old.stopReason == null || old.stopReason == next.stopReason)
            require(old.process == null || old.process == next.process)
            if (old.process == null && next.process != null) {
                require(old.phase in setOf(PtySessionRecord.Phase.STARTING, PtySessionRecord.Phase.CLOSING))
                require(next.phase in setOf(PtySessionRecord.Phase.RUNNING, PtySessionRecord.Phase.CLOSING))
            }
            val allowed =
                when (old.phase) {
                    PtySessionRecord.Phase.STARTING -> {
                        PtySessionRecord.Phase.entries.toSet()
                    }

                    PtySessionRecord.Phase.RUNNING -> {
                        setOf(
                            PtySessionRecord.Phase.RUNNING,
                            PtySessionRecord.Phase.CLOSING,
                            PtySessionRecord.Phase.STOPPED,
                            PtySessionRecord.Phase.UNKNOWN,
                        )
                    }

                    PtySessionRecord.Phase.CLOSING -> {
                        setOf(
                            PtySessionRecord.Phase.CLOSING,
                            PtySessionRecord.Phase.STOPPED,
                            PtySessionRecord.Phase.UNKNOWN,
                        )
                    }

                    PtySessionRecord.Phase.UNKNOWN -> {
                        setOf(PtySessionRecord.Phase.UNKNOWN)
                    }

                    PtySessionRecord.Phase.STOPPED -> {
                        error("Stopped PTY must have proof")
                    }
                }
            require(next.phase in allowed) { "PTY phase cannot rewind" }
        }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return File(directory, id + SUFFIX)
    }

    companion object {
        const val MAX_ENTRIES = 128
        private const val SUFFIX = ".pty"
        private val lock = Any()
    }
}
