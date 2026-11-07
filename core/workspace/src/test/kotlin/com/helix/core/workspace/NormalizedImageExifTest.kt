package com.helix.core.workspace

import com.helix.core.model.VisionLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Container framing tests; Android tests also check actual re-encoded image pixels at the provider boundary. */
class NormalizedImageExifTest {
    @Test fun jpegFindsCompleteExifSegmentsBeforeAndBetweenScans() {
        val exif = segment(0xe1, "Exif\u0000\u0000private-gps".toByteArray())
        assertFalse(check(jpeg(exif + scan), "jpeg"))
        assertFalse(check(jpeg(scan + byteArrayOf(12, 13) + exif + scan), "jpeg"))
    }

    @Test fun jpegDoesNotMistakePixelsOrOtherSegmentBodiesForAnExifSegment() {
        assertTrue(check(jpeg(segment(0xe0, "Exif\u0000\u0000".toByteArray()) + scan), "jpeg"))
        val entropy = "Exif\u0000\u0000".toByteArray() + byteArrayOf(0xff.toByte(), 0, 0xe1.toByte(), 1, 2)
        assertTrue(check(jpeg(scan + entropy), "jpeg"))
        assertTrue(check(jpeg(scan + byteArrayOf(0xff.toByte(), 0xd1.toByte(), 8)), "jpeg"))
    }

    @Test fun pngRejectsExifChunksAtEveryPositionWithoutSearchingPixelContents() {
        val pixels = chunk("IDAT", "eXIfExif\u0000\u0000".toByteArray())
        assertTrue(check(png(pixels), "png"))
        assertFalse(check(png(chunk("eXIf", byteArrayOf()) + pixels), "png"))
        assertFalse(check(png(pixels + chunk("eXIf", byteArrayOf(1, 2))), "png"))
    }

    @Test fun webpFindsExifChunksAndExtendedHeaderDeclaration() {
        val pixels = riffChunk("VP8 ", "EXIF".toByteArray())
        assertTrue(check(webp(pixels), "webp"))
        assertFalse(check(webp(riffChunk("EXIF", byteArrayOf(1)) + pixels), "webp"))
        assertFalse(check(webp(pixels + riffChunk("EXIF", byteArrayOf(1))), "webp"))
        assertFalse(check(webp(riffChunk("VP8X", byteArrayOf(8) + ByteArray(9)) + pixels), "webp"))
        assertTrue(check(webp(riffChunk("VP8X", byteArrayOf(16) + ByteArray(9)) + pixels), "webp"))
    }

    @Test fun malformedLengthsTruncationTrailingDataAndAnimationFailClosed() {
        val valid =
            listOf(
                "jpeg" to jpeg(scan),
                "png" to png(chunk("IDAT", byteArrayOf(1))),
                "webp" to webp(riffChunk("VP8L", byteArrayOf(1))),
            )
        valid.forEach { (type, bytes) ->
            assertTrue(check(bytes, type))
            assertFalse(check(bytes.copyOf(bytes.size - 1), type))
            assertFalse(check(bytes + byteArrayOf(1), type))
        }
        assertFalse(check(jpeg(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0xff.toByte(), 0xff.toByte())), "jpeg"))
        assertFalse(check(png(chunk("IDAT", byteArrayOf(1))).also { for (i in 8..11) it[i] = 0xff.toByte() }, "png"))
        assertFalse(
            check(webp(riffChunk("VP8 ", byteArrayOf(1))).also { for (i in 16..19) it[i] = 0xff.toByte() }, "webp"),
        )
        assertFalse(check(webp(riffChunk("ANMF", byteArrayOf()) + riffChunk("VP8 ", byteArrayOf(1))), "webp"))
    }

    @Test fun exactByteBudgetIsAcceptedAndOversizedOrUnsupportedInputIsRejected() {
        val exact = jpeg(scan + ByteArray(VisionLimits.MAX_NORMALIZED_RAW_BYTES.toInt() - scan.size - 4) { 1 })
        assertTrue(check(exact, "jpeg"))
        assertFalse(check(exact + byteArrayOf(1), "jpeg"))
        assertFalse(NormalizedImageExif.isExifFree(exact, "image/gif"))
    }

    @Test fun arbitraryTruncatedContainersNeverIndexOutsideTheirBoundedInput() {
        val random = Random(13)
        repeat(500) {
            val bytes = random.nextBytes(random.nextInt(0, 96))
            listOf("jpeg", "png", "webp").forEach { type -> check(bytes, type) }
        }
    }

    private fun check(
        bytes: ByteArray,
        type: String,
    ) = NormalizedImageExif.isExifFree(bytes, "image/$type")

    private val scan = segment(0xda, byteArrayOf())

    private fun jpeg(body: ByteArray) =
        byteArrayOf(0xff.toByte(), 0xd8.toByte()) + body + byteArrayOf(0xff.toByte(), 0xd9.toByte())

    private fun segment(
        marker: Int,
        body: ByteArray,
    ) = byteArrayOf(0xff.toByte(), marker.toByte()) + number(body.size + 2, 2, false) + body

    private fun png(body: ByteArray) =
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + chunk("IHDR", ByteArray(13)) + body +
            chunk("IEND", byteArrayOf())

    private fun chunk(
        type: String,
        body: ByteArray,
    ) = number(body.size, 4, false) + type.toByteArray() + body + ByteArray(4)

    private fun webp(body: ByteArray) =
        "RIFF".toByteArray() + number(body.size + 4, 4, true) + "WEBP".toByteArray() + body

    private fun riffChunk(
        type: String,
        body: ByteArray,
    ) = type.toByteArray() + number(body.size, 4, true) + body + ByteArray(body.size % 2)

    private fun number(
        value: Int,
        count: Int,
        little: Boolean,
    ) = ByteArray(count) { i ->
        (
            value ushr
                (8 * if (little) i else count - i - 1)
        ).toByte()
    }
}
