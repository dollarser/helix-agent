package com.helix.core.workspace

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
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
 *   sequence start (the encoding boundary), so the next read resumes at a clean boundary.
 *   A window too small to hold one complete character, or an incomplete sequence at EOF,
 *   is returned as opaque bytes so paging always advances without losing data;
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
    val sha256: String? = null,
) {
    companion object {
        /** The chunk unit a `read` tool caps at; the 10 MiB file is exactly this many chunks. */
        const val MAX_WINDOW_BYTES: Long = 1024L * 1024L

        /** Full-file identity is opt-in and shares the existing whole-file operation bound. */
        const val MAX_HASH_BYTES: Long = 32L * 1024L * 1024L

        private val utf8 = Charset.forName("UTF-8")

        /**
         * Bounded read of [file]: at most [maxBytes] bytes starting at [offset]. Never reads the
         * whole file unless [includeSha256] is requested; memory is bounded by [maxBytes].
         * @throws IllegalArgumentException on an invalid offset/maxBytes.
         * @throws IOException when the file cannot be read.
         */
        @JvmStatic
        fun read(
            file: Path,
            offset: Long,
            maxBytes: Long,
            includeSha256: Boolean = false,
            checkpoint: () -> Unit = {},
        ): ReadWindow =
            Files.newInputStream(file).use {
                read(it, Files.size(file), offset, maxBytes, includeSha256, checkpoint)
            }

        /** Streams the same bounded window from document providers; no local development copy. */
        fun read(
            input: java.io.InputStream,
            size: Long,
            offset: Long,
            maxBytes: Long,
            includeSha256: Boolean = false,
            checkpoint: () -> Unit = {},
        ): ReadWindow {
            require(size >= 0) { "File size is unavailable" }
            require(offset >= 0) { "offset must be >= 0 (got $offset)" }
            require(maxBytes in 1..MAX_WINDOW_BYTES) { "maxBytes must be 1..$MAX_WINDOW_BYTES (got $maxBytes)" }
            checkpoint()
            return if (includeSha256) {
                require(size <= MAX_HASH_BYTES) { "File exceeds the bounded hash limit" }
                val stream = FileReadHash(input, size, checkpoint)
                val window = readUnhashedWindow(stream, size, offset, maxBytes, checkpoint)
                val hash = stream.finish()
                checkpoint()
                window.copy(sha256 = hash)
            } else {
                readUnhashedWindow(input, size, offset, maxBytes, checkpoint)
            }
        }

        @Suppress("ThrowsCount") // Truncation and zero-progress reads are distinct failures in both loops.
        private fun readUnhashedWindow(
            input: java.io.InputStream,
            size: Long,
            offset: Long,
            maxBytes: Long,
            checkpoint: () -> Unit,
        ): ReadWindow {
            if (offset >= size) {
                // Stable EOF: at or past the end is the terminal window (no bytes, not an error).
                return ReadWindow(offset, 0, size, ContentProbe.Encoding.EMPTY, "", null, 0L, offset, true)
            }
            val length = minOf(size - offset, maxBytes).toInt()
            val window = ByteArray(length)
            run {
                // NOT InputStream.skipNBytes: that Java 11 method is missing from the API 29
                // platform (NoSuchMethodError on device, minSdk 29). Draining through read()
                // is exact on every level and cannot short-skip like skip() can.
                var remaining = offset
                val skipBuffer = ByteArray(8192)
                while (remaining > 0) {
                    checkpoint()
                    val skipped = input.read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), remaining).toInt())
                    if (skipped < 0) throw EOFException("file truncated below offset $offset")
                    if (skipped == 0) throw IOException("Read made no progress")
                    remaining -= skipped
                }
                var total = 0
                while (total < length) {
                    checkpoint()
                    val n = input.read(window, total, length - total)
                    if (n < 0) break
                    if (n == 0) throw IOException("Read made no progress")
                    total += n
                }
                if (total != length) throw EOFException("short read at offset $offset")
            }
            checkpoint()
            return decodeWindow(window, size, offset)
        }

        /**
         * Classifies [window] and, for a UTF-8 [encoding], drops a trailing partial multi-byte
         * sequence (the encoding boundary) so [text] never ends mid-character. [base64] carries
         * binary/UTF-16 content verbatim.
         *
         * Classification is prefix-aware (unlike [ContentProbe.probeBytes], which is all-or-nothing):
         * a window whose leading bytes are valid UTF-8 but whose FINAL sequence is cut by the
         * window boundary is still text when a complete prefix exists and EOF has not been reached.
         * The partial tail is dropped and [nextOffset] lands on the sequence start. Other windows
         * are returned as opaque bytes, preserving invalid or incomplete content and paging progress.
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
            val cutAfterText = !eof && prefixEnd > 0 && isCutTail(window, prefixEnd)
            if (prefixEnd == window.size || cutAfterText) {
                val text =
                    if (prefixEnd == window.size) {
                        decodeUtf8(window)
                    } else {
                        decodeUtf8(window.copyOfRange(0, prefixEnd))
                    }
                if (text != null) {
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

        @Suppress("SwallowedException") // Malformed text is returned as its original opaque bytes.
        private fun decodeUtf8(b: ByteArray): String? =
            try {
                utf8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }

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
