package com.helix.core.workspace

import com.helix.core.model.VisionLimits

/**
 * Checks complete EXIF container identifiers in the exact wire bytes, without decoding pixels.
 * This is not a claim that pixels or other metadata contain no private information. Malformed,
 * truncated, trailing, oversized and unsupported containers fail closed. The scan is O(bytes)
 * with constant extra memory and is only for images already required to be normalized.
 */
object NormalizedImageExif {
    fun isExifFree(
        bytes: ByteArray,
        mediaType: String,
    ): Boolean {
        if (bytes.isEmpty() || bytes.size > VisionLimits.MAX_NORMALIZED_RAW_BYTES) return false
        return when (mediaType) {
            "image/jpeg" -> jpeg(bytes)
            "image/png" -> png(bytes)
            "image/webp" -> webp(bytes)
            else -> false
        }
    }

    // Sequential fail-closed byte checks keep marker bounds and entropy-state transitions together.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
    private fun jpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes.u8(0) != 0xff || bytes.u8(1) != 0xd8) return false
        var offset = 2
        var entropy = false
        var sawScan = false
        while (offset < bytes.size) {
            if (entropy && bytes.u8(offset) != 0xff) {
                offset++
                continue
            }
            if (bytes.u8(offset++) != 0xff) return false
            while (offset < bytes.size && bytes.u8(offset) == 0xff) offset++
            if (offset >= bytes.size) return false
            val marker = bytes.u8(offset++)
            if (entropy && (marker == 0 || marker in 0xd0..0xd7)) continue
            entropy = false
            if (marker == 0xd9) return sawScan && offset == bytes.size
            if (marker == 0 || marker == 0xd8 || marker in 0xd0..0xd7) return false
            if (marker == 1) continue
            if (offset + 2 > bytes.size) return false
            val length = (bytes.u8(offset) shl 8) or bytes.u8(offset + 1)
            if (length < 2 || length > bytes.size - offset) return false
            if (marker == 0xe1 && length >= 8 && bytes.ascii(offset + 2, "Exif\u0000\u0000")) return false
            offset += length
            if (marker == 0xda) {
                entropy = true
                sawScan = true
            }
        }
        return false
    }

    // Each malformed chunk or EXIF marker rejects before advancing the bounded cursor.
    @Suppress("ReturnCount")
    private fun png(bytes: ByteArray): Boolean {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (bytes.size < signature.size || signature.indices.any { bytes[it] != signature[it] }) return false
        var offset = 8
        var header = false
        var pixels = false
        while (offset <= bytes.size - 12) {
            val length = bytes.u32(offset, littleEndian = false)
            if (length > bytes.size - offset - 12L) return false
            if (!header) {
                if (!bytes.ascii(offset + 4, "IHDR") || length != 13L) return false
                header = true
            }
            if (bytes.ascii(offset + 4, "eXIf")) return false
            if (bytes.ascii(offset + 4, "IDAT")) pixels = true
            val end = offset + 12 + length.toInt()
            if (bytes.ascii(offset + 4, "IEND")) return length == 0L && pixels && end == bytes.size
            offset = end
        }
        return false
    }

    // RIFF framing, bounded chunks and the EXIF flag are independently required at this wire boundary.
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun webp(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || !bytes.ascii(0, "RIFF") || !bytes.ascii(8, "WEBP") ||
            bytes.u32(4, littleEndian = true) != bytes.size - 8L
        ) {
            return false
        }
        var offset = 12
        var pixels = false
        while (offset <= bytes.size - 8) {
            val length = bytes.u32(offset + 4, littleEndian = true)
            val padded = length + (length and 1L)
            if (padded > bytes.size - offset - 8L) return false
            if (bytes.ascii(offset, "EXIF")) return false
            if (bytes.ascii(offset, "VP8X")) {
                if (length != 10L || bytes.u8(offset + 8) and 0x08 != 0) return false
            }
            // Normalization selects one frame; accepting nested animation would require another parser.
            if (bytes.ascii(offset, "ANIM") || bytes.ascii(offset, "ANMF")) return false
            if (bytes.ascii(offset, "VP8 ") || bytes.ascii(offset, "VP8L")) pixels = true
            offset += 8 + padded.toInt()
        }
        return pixels && offset == bytes.size
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u32(
        offset: Int,
        littleEndian: Boolean,
    ): Long {
        var value = 0L
        for (index in 0..3) value = (value shl 8) or u8(offset + if (littleEndian) 3 - index else index).toLong()
        return value
    }

    private fun ByteArray.ascii(
        offset: Int,
        value: String,
    ): Boolean = offset >= 0 && value.length <= size - offset && value.indices.all { u8(offset + it) == value[it].code }
}
