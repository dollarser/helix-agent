package com.helix.app.proot

import com.helix.tools.framework.ExecutionOwnership
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.UTFDataFormatException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Host admission identity only, not a second Runtime job journal. No cold bind on reads. */
internal class ExecutionControlUnreadable : IllegalStateException("Execution control record requires reset")

internal class ExecutionOwnershipStore(
    internal val file: File,
) : ExecutionOwnership.Store {
    override fun owners(): Set<ExecutionOwnership.Owner> = synchronized(lock) { readLocked() }

    override fun update(
        expected: Set<ExecutionOwnership.Owner>,
        replacement: Set<ExecutionOwnership.Owner>,
    ): Boolean =
        synchronized(lock) {
            if (readLocked() != expected) return@synchronized false
            require(replacement.size <= MAX_OWNERS)
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "execution admission directory unavailable" }
            val temporary = File.createTempFile("ownership-", ".pending", parent)
            try {
                FileOutputStream(temporary).use { stream ->
                    val data = DataOutputStream(stream)
                    data.writeInt(VERSION)
                    data.writeInt(replacement.size)
                    replacement.sortedWith(compareBy({ it.executionId }, { it.generation })).forEach {
                        require(it.executionId.length <= MAX_ID_CHARS && it.generation.length <= MAX_ID_CHARS)
                        data.writeUTF(it.executionId)
                        data.writeUTF(it.generation)
                    }
                    data.flush()
                    stream.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            } finally {
                temporary.delete()
            }
        }

    @Suppress("ThrowsCount") // Each format invariant maps to the same typed refusal; I/O errors remain distinct.
    private fun readLocked(): Set<ExecutionOwnership.Owner> {
        if (!file.exists()) return emptySet()
        if (file.length() !in 8..MAX_RECORD_BYTES) throw ExecutionControlUnreadable()
        return try {
            DataInputStream(FileInputStream(file)).use { data ->
                if (data.readInt() != VERSION) throw ExecutionControlUnreadable()
                val count = data.readInt()
                if (count !in 0..MAX_OWNERS) throw ExecutionControlUnreadable()
                val owners = List(count) { ExecutionOwnership.Owner(data.readUTF(), data.readUTF()) }.toSet()
                if (owners.size != count || data.read() != -1) throw ExecutionControlUnreadable()
                if (owners.any { it.executionId.length > MAX_ID_CHARS || it.generation.length > MAX_ID_CHARS }) {
                    throw ExecutionControlUnreadable()
                }
                owners
            }
        } catch (
            _: EOFException,
        ) {
            throw ExecutionControlUnreadable()
        } catch (
            _: UTFDataFormatException,
        ) {
            throw ExecutionControlUnreadable()
        } catch (_: IllegalArgumentException) {
            throw ExecutionControlUnreadable()
        }
    }

    companion object {
        // All callers live in the application process. Runtime receives bound identities over IPC;
        // it never reads/writes this host admission store. Multiple wrappers share this CAS lock.
        private val lock = Any()
        private const val VERSION = 1
        private const val MAX_OWNERS = 1024
        private const val MAX_ID_CHARS = 128
        private const val MAX_RECORD_BYTES = 1024L * 1024L
    }
}
