package com.helix.core.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Bounded MIME / encoding detection (HXA-041: bounded MIME/encoding detection).
 *
 * "Bounded" means detection reads only a fixed prefix of the file — [SAMPLE_BYTES] — never the
 * whole content. A large binary therefore costs the same as a small one, and detection can never
 * be used as a DoS vector (reading an unbounded file into memory). The result is a [Result]
 * carrying a best-effort MIME type, an encoding classification and a short, bounded content hash.
 *
 * Detection is deliberately conservative: when the prefix is ambiguous it reports binary /
 * `application/octet-stream` rather than guessing, so a caller that needs certainty treats
 * non-text as opaque and fails closed on its own policy.
 */
@Suppress("TooManyFunctions") // one small function per detection stage (magic / utf-8 / mime helpers)
object ContentProbe {
    /** How many leading bytes are sampled for detection. 8 KiB covers every common magic. */
    const val SAMPLE_BYTES: Int = 8 * 1024

    /** MIME assigned when the magic table has no match (the safe default). */
    private const val OCTET_STREAM = "application/octet-stream"

    /**
     * A detected file's bounded fingerprint.
     *
     * @param mimeType best-effort MIME (or octet-stream).
     * @param encoding text-encoding classification of the sampled prefix.
     * @param isText true when [encoding] is a text form.
     * @param sizeBytes total file size, or -1 when the file does not exist.
     * @param sampleCrc32 bounded CRC32 over the sampled prefix — a cheap, stable fingerprint, not a
     *   hash of the whole file.
     * @param truncated true when the file was larger than [SAMPLE_BYTES] (the probe is then a
     *   prefix, not the whole file).
     */
    class Result(
        val mimeType: String,
        val encoding: Encoding,
        val isText: Boolean,
        val sizeBytes: Long,
        val sampleCrc32: Long,
        val truncated: Boolean,
    ) {
        override fun toString(): String =
            "Result(mime=$mimeType, encoding=$encoding, isText=$isText, size=$sizeBytes, truncated=$truncated)"
    }

    /** Text-encoding classification of the sampled prefix. */
    enum class Encoding {
        /** UTF-8 decoded cleanly with no NUL bytes. */
        UTF8,

        /** UTF-16 with a BOM. */
        UTF16,

        /** A NUL byte or invalid UTF-8 was seen: treat as binary. */
        BINARY,

        /** The file is empty; encoding is vacuous. */
        EMPTY,
    }

    /**
     * Probes [source]. The file need not exist — a missing file yields a size of -1 and an
     * octet-stream / [Encoding.EMPTY] probe so callers can branch explicitly.
     * @throws IOException when the file exists but the prefix cannot be read.
     */
    fun probe(source: Path): Result {
        if (!Files.exists(source) || !Files.isRegularFile(source)) return emptyResult(-1)
        val size = Files.size(source)
        return probeBytes(readPrefix(source, size), size)
    }

    /** Probes an in-memory [bytes] array of a file of total [sizeBytes] (for tests and streams). */
    fun probeBytes(
        bytes: ByteArray,
        sizeBytes: Long,
    ): Result {
        if (bytes.isEmpty()) return emptyResult(sizeBytes)
        val crc = CRC32().apply { update(bytes) }.value
        val encoding = detectEncoding(bytes)
        val isText = encoding == Encoding.UTF8 || encoding == Encoding.UTF16
        return Result(detectMime(bytes), encoding, isText, sizeBytes, crc, sizeBytes > bytes.size)
    }

    /** The octet-stream / EMPTY probe used for missing or zero-length files. */
    private fun emptyResult(sizeBytes: Long): Result =
        Result(
            mimeType = OCTET_STREAM,
            encoding = Encoding.EMPTY,
            isText = false,
            sizeBytes = sizeBytes,
            sampleCrc32 = 0L,
            truncated = false,
        )

    /** Reads at most [SAMPLE_BYTES] leading bytes of [source] (bounded by design). */
    private fun readPrefix(
        source: Path,
        size: Long,
    ): ByteArray {
        val wanted = minOf(size, SAMPLE_BYTES.toLong()).toInt()
        val sample = ByteArray(wanted)
        var total = 0
        Files.newInputStream(source, StandardOpenOption.READ).use { input ->
            while (total < sample.size) {
                val n = input.read(sample, total, sample.size - total)
                if (n < 0) break
                total += n
            }
        }
        return if (total == sample.size) sample else sample.copyOf(total)
    }

    private fun detectEncoding(bytes: ByteArray): Encoding =
        when {
            bytes.size >= 2 &&
                (
                    (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                        (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                ) -> Encoding.UTF16

            bytes.any { it == 0.toByte() } -> Encoding.BINARY

            isValidUtf8(bytes) -> Encoding.UTF8

            else -> Encoding.BINARY
        }

    /** True when [bytes] is a valid UTF-8 sequence (lead byte + paired continuations). */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val length = nextUtf8Length(bytes, i)
            if (length == 0) return false
            i += length
        }
        return true
    }

    /** Length of the UTF-8 sequence at [index], or 0 when the lead/continuations are invalid. */
    private fun nextUtf8Length(
        bytes: ByteArray,
        index: Int,
    ): Int {
        val b = bytes[index].toInt() and 0xFF
        val length =
            when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                else -> 4
            }
        val validLead = b < 0x80 || b in 0xC2..0xF4
        return if (validLead && continuationsValid(bytes, index, length)) length else 0
    }

    /** True when every byte from [index]+1 up to [index]+[length) is a valid continuation. */
    @Suppress("ReturnCount") // one return per distinct outcome: out-of-sample tail / bad continuation / all valid
    private fun continuationsValid(
        bytes: ByteArray,
        index: Int,
        length: Int,
    ): Boolean {
        // A lead byte whose continuation bytes run past the sampled prefix (a bounded read window
        // can end mid-sequence) is NOT valid UTF-8 over this sample: report false instead of
        // reading past the end (HXA-042 exposed this via a `read` window straddling a boundary).
        if (index + length > bytes.size) return false
        var j = index + 1
        while (j < index + length) {
            if ((bytes[j].toInt() and 0xC0) != 0x80) return false
            j++
        }
        return true
    }

    /**
     * Best-effort MIME from a data-driven magic table, then a small set of text heuristics;
     * octet-stream when nothing matches. Kept data-driven so adding a type is a table change.
     */
    @Suppress("ReturnCount") // one return per detection stage: webp / magic table / text heuristic
    private fun detectMime(bytes: ByteArray): String {
        // WebP is RIFF-based: "RIFF" at 0..3 AND "WEBP" at 8..11 (a plain prefix cannot
        // express the second position, so it is checked before the magic table).
        if (isWebpContainer(bytes)) return "image/webp"
        for ((magic, mime) in MIME_MAGICS) {
            if (bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }) {
                return mime
            }
        }
        return if (bytes.startsWithAscii("<?xml")) {
            "application/xml"
        } else if (bytes.startsWithAscii("{") || bytes.startsWithAscii("[")) {
            "application/json"
        } else {
            OCTET_STREAM
        }
    }

    /** The RIFF container signature: "RIFF" at 0..3 AND the "WEBP" fourcc at 8..11. */
    private fun isWebpContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riff = intArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
        val webp = intArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"
        return riff.indices.all { bytes[it].toInt() and 0xFF == riff[it] } &&
            (8 until 12).all { bytes[it].toInt() and 0xFF == webp[it - 8] }
    }

    private fun ByteArray.startsWithAscii(s: String): Boolean =
        size >= s.length && s.indices.all { this[it].toInt().toChar() == s[it] }

    /** Recognized magic prefixes (most specific first); the mime follows each magic. */
    private val MIME_MAGICS: List<Pair<ByteArray, String>> =
        listOf(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) to "image/png",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to "image/jpeg",
            byteArrayOf(0x47, 0x49, 0x46, 0x38) to "image/gif",
            byteArrayOf(0x25, 0x50, 0x44, 0x46) to "application/pdf",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) to "application/zip",
            byteArrayOf(0x1F.toByte(), 0x8B.toByte()) to "application/gzip",
            byteArrayOf(0x42, 0x5A, 0x68) to "application/x-bzip2",
            byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A) to "application/x-xz",
            byteArrayOf(0x52, 0x41, 0x52, 0x21) to "application/vnd.rar",
        )
}
