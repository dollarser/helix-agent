package com.helix.app.vision

import com.helix.core.model.VisionLimits
import com.helix.core.workspace.ContentProbe
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Validate the exact bounded bytes that will be consumed, not a hash from a previous file read. */
internal object VerifiedImageBytes {
    fun read(
        size: Long,
        sha256: String,
        mediaType: String,
        open: () -> InputStream,
    ): ByteArray {
        require(size in 1..VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong()) { "Image exceeds byte budget" }
        require(mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES) { "Image media type is unsupported" }
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" }) { "Invalid image hash" }
        checkActive()
        val buffer = ByteArray(size.toInt() + 1)
        var count = 0
        open().use { input ->
            while (count < buffer.size) {
                checkActive()
                val read = input.read(buffer, count, minOf(8192, buffer.size - count))
                if (read < 0) break
                require(read > 0) { "Image reader made no progress" }
                count += read
            }
        }
        require(count.toLong() == size) { "Image size changed" }
        checkActive()
        val bytes = buffer.copyOf(count)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual == sha256) { "Image hash changed" }
        require(ContentProbe.probeBytes(bytes, size).mimeType == mediaType) { "Image media type changed" }
        return bytes
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Image read cancelled")
    }
}
