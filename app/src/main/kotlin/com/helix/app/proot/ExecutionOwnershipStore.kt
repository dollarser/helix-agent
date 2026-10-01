package com.helix.app.proot

import com.helix.tools.framework.ExecutionOwnership
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Host admission identity only, not a second Runtime job journal. No cold bind on reads. */
internal class ExecutionOwnershipStore(
    private val file: File,
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

    private fun readLocked(): Set<ExecutionOwnership.Owner> {
        if (!file.exists()) return emptySet()
        check(file.length() in 5..MAX_RECORD_BYTES) { "execution admission record size invalid" }
        return DataInputStream(FileInputStream(file)).use { data ->
            val count =
                when (data.readInt()) {
                    1 -> if (data.readBoolean()) 1 else 0
                    VERSION -> data.readInt()
                    else -> error("execution admission version unsupported")
                }
            check(count in 0..MAX_OWNERS) { "execution identity count invalid" }
            val owners = List(count) { ExecutionOwnership.Owner(data.readUTF(), data.readUTF()) }.toSet()
            check(owners.size == count) { "duplicate execution identity" }
            check(data.read() == -1) { "execution admission trailing data" }
            check(
                owners.all { it.executionId.length <= MAX_ID_CHARS && it.generation.length <= MAX_ID_CHARS },
            )
            owners
        }
    }

    companion object {
        // All callers live in the application process. Runtime receives bound identities over IPC;
        // it never reads/writes this host admission store. Multiple wrappers share this CAS lock.
        private val lock = Any()
        private const val VERSION = 2
        private const val MAX_OWNERS = 1024
        private const val MAX_ID_CHARS = 128
        private const val MAX_RECORD_BYTES = 1024L * 1024L
    }
}
