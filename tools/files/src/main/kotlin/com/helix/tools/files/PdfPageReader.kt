package com.helix.tools.files

import com.helix.tools.framework.ExecutableToolCall
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant

/** Only the Android composition owns the isolated native parser. */
fun interface PdfPageReader {
    fun read(
        call: ExecutableToolCall,
        path: String,
        page: Int,
        image: Boolean,
    ): PdfPage
}

data class PdfPage(
    val pageCount: Int,
    val sourceSha256: String,
    val text: String,
    val textTruncated: Boolean,
    val textExtractionSupported: Boolean,
    val png: ByteArray? = null,
)

class PdfReadException(
    val code: String,
) : java.io.IOException(code)

/** Immutable bounded source snapshot; private staging is not a model-selected write target. */
object PdfSourceSnapshot {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_TEXT = 20_000
    const val MAX_PNG_BYTES = 3 * 1024 * 1024

    fun checkActive(call: ExecutableToolCall) {
        if (call.cancel.isCancelled()) throw PdfReadException("CANCELLED")
        if (!Instant.now().isBefore(call.deadline)) throw PdfReadException("TIMED_OUT")
    }

    fun copy(
        call: ExecutableToolCall,
        source: InputStream,
        destination: OutputStream,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val magic = ByteArray(5)
        var total = 0L
        while (true) {
            checkActive(call)
            val count = source.read(buffer)
            if (count < 0) break
            if (total + count > MAX_BYTES) throw PdfReadException("PDF_TOO_LARGE")
            for (index in 0 until minOf(count, (5 - total).coerceAtLeast(0).toInt())) {
                magic[total.toInt() + index] = buffer[index]
            }
            digest.update(buffer, 0, count)
            destination.write(buffer, 0, count)
            total += count
        }
        checkActive(call)
        if (!magic.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) throw PdfReadException("NOT_A_PDF")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
