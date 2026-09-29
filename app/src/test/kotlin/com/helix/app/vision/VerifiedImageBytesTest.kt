package com.helix.app.vision

import com.helix.core.model.VisionLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class VerifiedImageBytesTest {
    private val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 16, 74, 70, 0, 1)

    private fun hash(value: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    @Test fun readsAndValidatesOneBoundedSnapshot() {
        val actual = VerifiedImageBytes.read(bytes.size.toLong(), hash(bytes), "image/jpeg") { bytes.inputStream() }
        assertArrayEquals(bytes, actual)
    }

    @Test fun oversizeMetadataIsRejectedBeforeOpeningSource() {
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedImageBytes.read(VisionLimits.MAX_NORMALIZED_RAW_BYTES + 1L, hash(bytes), "image/jpeg") {
                opened = true
                bytes.inputStream()
            }
        }
        assertFalse(opened)
    }

    @Test fun growingOrInfiniteSourceStopsAtDeclaredSizePlusOneAndCloses() {
        var readBytes = 0
        var closed = false
        val input =
            object : InputStream() {
                override fun read(): Int = error("bulk read expected")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    buffer.fill(1, offset, offset + length)
                    readBytes += length
                    return length
                }

                override fun close() {
                    closed = true
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedImageBytes.read(bytes.size.toLong(), hash(bytes), "image/jpeg") { input }
        }
        assertEquals(bytes.size + 1, readBytes)
        assertTrue(closed)
    }

    @Test fun truncationAndSameSizeMutationAreRejected() {
        for (changed in listOf(bytes.dropLast(1).toByteArray(), bytes.copyOf().also { it[it.lastIndex] = 2 })) {
            assertThrows(IllegalArgumentException::class.java) {
                VerifiedImageBytes.read(bytes.size.toLong(), hash(bytes), "image/jpeg") { changed.inputStream() }
            }
        }
    }

    @Test fun mimeForgeryIsRejectedEvenWithMatchingHash() {
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedImageBytes.read(bytes.size.toLong(), hash(bytes), "image/png") { bytes.inputStream() }
        }
    }

    @Test fun stalledInputDoesNotSpinForever() {
        val input =
            object : InputStream() {
                override fun read(): Int = error("bulk read expected")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedImageBytes.read(bytes.size.toLong(), hash(bytes), "image/jpeg") { input }
        }
    }

    @Test fun cancelledReadDoesNotOpenSource() {
        var opened = false
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) {
                VerifiedImageBytes.read(bytes.size.toLong(), hash(bytes), "image/jpeg") {
                    opened = true
                    bytes.inputStream()
                }
            }
            assertFalse(opened)
        } finally {
            Thread.interrupted()
        }
    }
}
