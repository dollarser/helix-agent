package com.helix.core.workspace

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * The bounded read window of one `read` call (HXA-042). A read is always bounded by an offset and
 * a byte cap so the model can page through arbitrarily large files (the roadmap's 10 MiB case)
 * chunk by chunk without ever pulling the whole file into one output.
 *
 * Invariants:
 * - [windowLength] bytes are returned, at most [MAX_WINDOW_BYTES]; a caller reading
 *   [MAX_WINDOW_BYTES] at a time never re-reads or skips a byte;
 * - for a UTF-8 [encoding], [text] decodes WITHOUT a trailing partial character: a multi-byte
 *   sequence straddling the window end is dropped from [text] and [nextOffset] stops at the
 *   sequence start (the encoding boundary), so the next read resumes at a clean boundary;
 * - [eof] means "the file is fully consumed from [offset]", not "[text] is non-empty".
 */
data class ReadWindow(
    val offset: Long,
    val windowLength: Int,
    val sizeBytes: Long,
    val encoding: ContentProbe.Encoding,
    val text: String?,
    val base64: String?,
    val consumedBytes: Long,
    val nextOffset: Long,
    val eof: Boolean,
) {
    companion object {
        /** The chunk unit a `read` tool caps at; the 10 MiB file is exactly this many chunks. */
        const val MAX_WINDOW_BYTES: Long = 1024L * 1024L

        private val utf8 = Charset.forName("UTF-8")

        /**
         * Bounded read of [file]: at most [maxBytes] bytes starting at [offset]. Never reads the
         * whole file; memory is bounded by [maxBytes].
         * @throws IllegalArgumentException on an invalid offset/maxBytes.
         * @throws IOException when the file cannot be read.
         */
        @JvmStatic
        fun read(
            file: Path,
            offset: Long,
            maxBytes: Long,
        ): ReadWindow {
            require(offset >= 0) { "offset must be >= 0 (got $offset)" }
            require(maxBytes in 1..MAX_WINDOW_BYTES) { "maxBytes must be 1..$MAX_WINDOW_BYTES (got $maxBytes)" }
            val size = Files.size(file)
            if (offset >= size) {
                // Stable EOF: at or past the end is the terminal window (no bytes, not an error).
                return ReadWindow(offset, 0, size, ContentProbe.Encoding.EMPTY, "", null, 0L, offset, true)
            }
            val length = minOf(size - offset, maxBytes).toInt()
            val window = ByteArray(length)
            Files.newInputStream(file).use { input ->
                input.skipNBytes(offset)
                var total = 0
                while (total < length) {
                    val n = input.read(window, total, length - total)
                    if (n < 0) break
                    total += n
                }
                require(total == length) { "short read at offset $offset" }
            }
            return decodeWindow(window, size, offset)
        }

        /**
         * Classifies [window] and, for a UTF-8 [encoding], drops a trailing partial multi-byte
         * sequence (the encoding boundary) so [text] never ends mid-character. [base64] carries
         * binary/UTF-16 content verbatim.
         *
         * Classification is prefix-aware (unlike [ContentProbe.probeBytes], which is all-or-nothing):
         * a window whose leading bytes are valid UTF-8 but whose FINAL sequence is cut by the
         * [maxBytes] boundary is still text — the partial tail is dropped and [nextOffset] lands on
         * the sequence start. Only a NUL byte, a UTF-16 BOM, or a genuinely invalid byte (not a cut
         * sequence) yields BINARY, because those mean the content is not text at all.
         */
        @Suppress("ReturnCount") // one return per distinct window classification (empty / binary / text / opaque)
        private fun decodeWindow(
            window: ByteArray,
            size: Long,
            offset: Long,
        ): ReadWindow {
            if (window.isEmpty()) {
                return ReadWindow(offset, 0, size, ContentProbe.Encoding.EMPTY, "", null, 0L, offset, true)
            }
            val eof = offset + window.size >= size
            val next = offset + window.size.toLong()
            // Not text at all: a UTF-16 BOM or a NUL byte.
            if (isUtf16Bom(window) || window.any { it == 0.toByte() }) {
                val enc = if (isUtf16Bom(window)) ContentProbe.Encoding.UTF16 else ContentProbe.Encoding.BINARY
                return ReadWindow(offset, window.size, size, enc, null, base64(window), window.size.toLong(), next, eof)
            }
            val prefixEnd = utf8PrefixEnd(window)
            // Whole window valid, or a valid prefix whose only tail is a cut sequence → text.
            if (prefixEnd == window.size || isCutTail(window, prefixEnd)) {
                val text =
                    if (prefixEnd == window.size) {
                        decodeUtf8(window)
                    } else {
                        decodeUtf8(window.copyOfRange(0, prefixEnd))
                    }
                val consumed = prefixEnd.toLong()
                return ReadWindow(
                    offset,
                    window.size,
                    size,
                    ContentProbe.Encoding.UTF8,
                    text,
                    null,
                    consumed,
                    offset + consumed,
                    eof,
                )
            }
            // A real invalid byte (not a cut tail): opaque.
            return ReadWindow(
                offset,
                window.size,
                size,
                ContentProbe.Encoding.BINARY,
                null,
                base64(window),
                window.size.toLong(),
                next,
                eof,
            )
        }

        private fun isUtf16Bom(b: ByteArray): Boolean =
            b.size >= 2 &&
                ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))

        private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        private fun decodeUtf8(b: ByteArray): String =
            utf8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(b))
                .toString()

        /**
         * The number of leading bytes of [w] that form complete, valid UTF-8 sequences — a
         * character boundary. Stops at the first byte that is an invalid lead/continuation or a
         * lead whose sequence runs past the end of the sample (a cut by the read window).
         */
        @Suppress("ReturnCount") // each return is a distinct scanner stop (boundary / cut / bad continuation / end)
        private fun utf8PrefixEnd(w: ByteArray): Int {
            var i = 0
            while (i < w.size) {
                val b = w[i].toInt() and 0xFF
                val length =
                    when {
                        b < 0x80 -> 1
                        b in 0xC2..0xDF -> 2
                        b in 0xE0..0xEF -> 3
                        b in 0xF0..0xF4 -> 4
                        else -> return i // invalid lead: a character boundary here
                    }
                if (i + length > w.size) return i // a complete boundary, then a cut sequence
                for (k in 1 until length) {
                    if ((w[i + k].toInt() and 0xC0) != 0x80) return i // bad continuation
                }
                i += length
            }
            return i
        }

        /**
         * True when [w][prefixEnd] is a valid UTF-8 lead whose full sequence does not fit in the
         * remaining bytes and every remaining byte is a valid continuation — i.e. the tail is a
         * single sequence cut by the window, not real garbage.
         */
        @Suppress("ReturnCount") // each return is a distinct classification outcome of the tail
        private fun isCutTail(
            w: ByteArray,
            prefixEnd: Int,
        ): Boolean {
            if (prefixEnd >= w.size) return false
            val lead = w[prefixEnd].toInt() and 0xFF
            val need =
                when {
                    lead < 0x80 -> 1
                    lead in 0xC2..0xDF -> 2
                    lead in 0xE0..0xEF -> 3
                    lead in 0xF0..0xF4 -> 4
                    else -> 0
                }
            val tail = w.size - prefixEnd
            if (need == 0 || need <= tail) return false
            for (k in 1 until tail) {
                if ((w[prefixEnd + k].toInt() and 0xC0) != 0x80) return false
            }
            return true
        }
    }
}
