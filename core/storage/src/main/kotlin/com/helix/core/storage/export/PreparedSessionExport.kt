package com.helix.core.storage.export

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** A finished local projection, not yet a successful user delivery. Owned by exactly one export. */
class PreparedSessionExport internal constructor(
    private val file: File,
    val exportId: String,
    val size: Long,
    private val sha256: ByteArray,
) : AutoCloseable {
    /** Owns and closes [output]. Returning normally includes successful output close, not cloud sync. */
    fun deliver(
        output: OutputStream,
        checkCancelled: () -> Unit,
        onProgress: (Long) -> Unit = {},
    ) {
        output.use { destination ->
            checkCancelled()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(SessionExportFormat.COPY_BUFFER_BYTES)
                var count = input.read(buffer)
                while (count != -1) {
                    checkCancelled()
                    require(count <= size - copied) { "Prepared export changed" }
                    destination.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copied += count
                    onProgress(copied)
                    count = input.read(buffer)
                }
            }
            check(copied == size && MessageDigest.isEqual(digest.digest(), sha256)) { "Prepared export changed" }
            checkCancelled()
        }
    }

    override fun close() {
        if (file.exists() && !file.delete()) throw IOException("Could not remove prepared export")
    }
}
