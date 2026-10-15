package com.helix.runtime.proot.core

import org.tukaani.xz.SingleXZInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** APK storage encoding only. The installer still authenticates the original locked tar bytes. */
object RootFsCompression {
    private const val MAX_TAR_BYTES = 256L * 1024 * 1024
    private const val MAX_ENCODED_BYTES = 64L * 1024 * 1024
    private const val DECODER_MEMORY_KIB = 16 * 1024

    fun open(
        source: () -> InputStream,
        expectedTarBytes: Long,
    ): InputStream {
        require(expectedTarBytes in 1..MAX_TAR_BYTES) { "Invalid RootFS tar byte limit" }
        val encoded = LimitedStream(source(), MAX_ENCODED_BYTES).buffered(64 * 1024)
        val decoder =
            try {
                SingleXZInputStream(encoded, DECODER_MEMORY_KIB)
            } catch (failure: IOException) {
                try {
                    encoded.close()
                } catch (closeFailure: IOException) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        return object : LimitedStream(decoder, expectedTarBytes) {
            private var finished = false

            override fun endOfStream() {
                if (finished) return
                if (bytesRead != expectedTarBytes) throw IOException("RootFS decoded size differs from lock")
                // Require the footer/checksum and exact end of the encoded asset. Concatenated
                // streams, padding or trailing data are not part of the single-stream format.
                if (encoded.read() != -1) throw IOException("Trailing data after RootFS XZ stream")
                finished = true
            }
        }
    }

    private open class LimitedStream(
        private val source: InputStream,
        private val limit: Long,
    ) : InputStream() {
        protected var bytesRead = 0L
            private set
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IndexOutOfBoundsException("Invalid RootFS read offset or length")
            }
            if (length == 0) return 0
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("RootFS read interrupted")
            if (bytesRead > limit) throw IOException("RootFS stream exceeds byte limit")
            val count = source.read(bytes, offset, minOf(length.toLong(), limit - bytesRead + 1).toInt())
            if (count < 0) {
                endOfStream()
            } else {
                bytesRead += count
                if (bytesRead > limit) throw IOException("RootFS stream exceeds byte limit")
            }
            return count
        }

        protected open fun endOfStream() = Unit

        override fun close() = source.close()
    }
}
