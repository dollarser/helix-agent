package com.helix.feature.files

import java.io.InputStream
import java.security.MessageDigest

/**
 * The post-export re-read verification (HXA-058: 导出后重新读取/校验可得证据时才显示 verified).
 * The export pipeline already reports what the PLATFORM confirms (bytes written + the
 * destination's self-reported size re-check). On top of that, this verifier RE-READS the
 * destination bytes and compares them byte-for-byte (SHA-256 + exact length) against what was
 * written.
 *
 * Any missing evidence fails the verification (not the export): an unreadable destination, a
 * truncated re-read, an extra byte, a hash mismatch or an I/O error all yield false. The export
 * result then reports only the platform-confirmed facts (COMPLETED + size re-check), never a
 * byte-level "verified" the evidence does not support.
 */
object SafExportVerifier {
    /**
     * Re-reads [uri] through [reReader] and verifies the bytes against [expectedSha256] /
     * [expectedSizeBytes] (the pipeline's written bytes + hash).
     * @return true only when the re-read is complete, exact and hash-equal; false for ANY missing
     *   or mismatching evidence (fail closed).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // an unreadable destination = missing evidence
    fun reReadVerified(
        uri: String,
        reReader: SafDestinationReReader,
        expectedSha256: String?,
        expectedSizeBytes: Long,
    ): Boolean {
        if (expectedSha256 == null || expectedSizeBytes < 0) return false
        val stream =
            try {
                reReader.openStream(uri)
            } catch (e: Exception) {
                null // an unreadable destination: the evidence is missing, not the export's fault
            }
        return stream != null && readExactly(stream, expectedSha256, expectedSizeBytes)
    }

    /** Reads exactly [expectedSizeBytes] bytes from [input] and compares the SHA-256. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any re-read I/O failure = missing evidence
    private fun readExactly(
        input: InputStream,
        expectedSha256: String,
        expectedSizeBytes: Long,
    ): Boolean =
        try {
            input.use { stream -> verifyStream(stream, expectedSha256, expectedSizeBytes) }
        } catch (e: Exception) {
            false
        }

    /**
     * The byte-level check: read until the exact written size (fewer bytes = truncated, one
     * extra readable byte = the destination holds MORE than the export wrote) and compare the
     * SHA-256 of exactly those bytes.
     */
    private fun verifyStream(
        stream: InputStream,
        expectedSha256: String,
        expectedSizeBytes: Long,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK)
        var remaining = expectedSizeBytes
        while (remaining > 0) {
            val n = stream.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
            if (n < 0) return false // truncated: fewer bytes than were written
            digest.update(buffer, 0, n)
            remaining -= n
        }
        val extra = stream.read() >= 0
        return !extra && digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256
    }

    private const val CHUNK: Int = 64 * 1024
}
