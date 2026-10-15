package com.helix.runtime.proot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.MemoryLimitException
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

class RootFsCompressionTest {
    private val data = "locked RootFS bytes".repeat(100).toByteArray()

    private fun encode(
        bytes: ByteArray = data,
        options: LZMA2Options = LZMA2Options(1),
    ): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                XZOutputStream(output, options).use { it.write(bytes) }
            }.toByteArray()

    private fun decode(
        bytes: ByteArray,
        size: Long = data.size.toLong(),
    ): ByteArray = RootFsCompression.open({ ByteArrayInputStream(bytes) }, size).use { it.readBytes() }

    @Test
    fun `single stream decodes exactly and closes owned input`() {
        var closed = false
        val source =
            object : ByteArrayInputStream(encode()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        RootFsCompression.open({ source }, data.size.toLong()).use {
            assertArrayEquals(data, it.readBytes())
        }
        assertTrue(closed)
    }

    @Test
    fun `invalid expected size never opens source`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootFsCompression.open({ error("must not open") }, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootFsCompression.open({ error("must not open") }, 256L * 1024 * 1024 + 1)
        }
    }

    @Test
    fun `decoded overrun and underrun both fail`() {
        assertThrows(IOException::class.java) { decode(encode(), data.size.toLong() - 1) }
        assertThrows(IOException::class.java) { decode(encode(), data.size.toLong() + 1) }
    }

    @Test
    fun `truncated footer and corrupted payload fail`() {
        val encoded = encode()
        assertThrows(IOException::class.java) { decode(encoded.copyOf(encoded.size - 3)) }
        val corrupted = encoded.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { decode(corrupted) }
    }

    @Test
    fun `concatenated streams and trailing padding fail`() {
        val encoded = encode()
        assertThrows(IOException::class.java) { decode(encoded + encoded) }
        assertThrows(IOException::class.java) { decode(encoded + ByteArray(4)) }
    }

    @Test
    fun `oversized dictionary fails before allocating it`() {
        val options = LZMA2Options(1).apply { dictSize = 32 * 1024 * 1024 }
        assertThrows(MemoryLimitException::class.java) { decode(encode(options = options)) }
    }

    @Test
    fun `bad header closes input`() {
        var closed = false
        val source =
            object : ByteArrayInputStream(byteArrayOf(0, 1)) {
                override fun close() {
                    closed = true
                }
            }
        assertThrows(IOException::class.java) { RootFsCompression.open({ source }, 20) }
        assertTrue(closed)
    }

    @Test
    fun `interruption stops reads and preserves interrupt status`() {
        RootFsCompression.open({ ByteArrayInputStream(encode()) }, data.size.toLong()).use { stream ->
            try {
                Thread.currentThread().interrupt()
                assertThrows(InterruptedIOException::class.java) { stream.read() }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }
}
