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
    override fun read(): ExecutionOwnership.Owner? = synchronized(lock) { readLocked() }

    override fun compareAndSet(
        expected: ExecutionOwnership.Owner?,
        replacement: ExecutionOwnership.Owner?,
    ): Boolean =
        synchronized(lock) {
            if (readLocked() != expected) return@synchronized false
            val parent = requireNotNull(file.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "execution admission directory unavailable" }
            val temporary = File.createTempFile("ownership-", ".pending", parent)
            try {
                FileOutputStream(temporary).use { stream ->
                    val data = DataOutputStream(stream)
                    data.writeInt(VERSION)
                    data.writeBoolean(replacement != null)
                    replacement?.let {
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

    private fun readLocked(): ExecutionOwnership.Owner? {
        if (!file.exists()) return null
        check(file.length() in 5..MAX_RECORD_BYTES) { "execution admission record size invalid" }
        return DataInputStream(FileInputStream(file)).use { data ->
            check(data.readInt() == VERSION) { "execution admission version unsupported" }
            val owner = if (data.readBoolean()) ExecutionOwnership.Owner(data.readUTF(), data.readUTF()) else null
            check(data.read() == -1) { "execution admission trailing data" }
            check(
                owner == null || (owner.executionId.length <= MAX_ID_CHARS && owner.generation.length <= MAX_ID_CHARS),
            )
            owner
        }
    }

    companion object {
        // All callers live in the application process. Runtime receives bound identities over IPC;
        // it never reads/writes this host admission store. Multiple wrappers share this CAS lock.
        private val lock = Any()
        private const val VERSION = 1
        private const val MAX_ID_CHARS = 128
        private const val MAX_RECORD_BYTES = 1024L
    }
}
