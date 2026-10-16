package com.helix.core.workspace

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Hashes the same stream that supplies the window; never reopens a possibly changed file. */
internal class FileReadHash(
    private val source: InputStream,
    private val expectedSize: Long,
    private val checkpoint: () -> Unit,
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        checkpoint()
        // Read at most one extra byte to detect growth; the declared size was already bounded.
        val limit = minOf(length.toLong(), expectedSize - count + 1).toInt()
        val read = source.read(bytes, offset, limit)
        if (read == 0) throw IOException("File read made no progress")
        if (read > 0) {
            count += read
            if (count > expectedSize) throw IOException("File size changed during read")
            digest.update(bytes, offset, read)
        }
        checkpoint()
        return read
    }

    fun finish(): String {
        val buffer = ByteArray(8192)
        while (read(buffer, 0, buffer.size) >= 0) {
            // Drain only the remainder: prefix and window bytes already contributed to the hash.
        }
        if (count != expectedSize) throw IOException("File size changed during read")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
