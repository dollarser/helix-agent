package com.helix.app.git

import com.helix.jgit.AndroidInputStreams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class JgitAndroidStreamsTest {
    @Test fun partialReadsAndEofRespectTheRequestedRange() {
        val input =
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(bytes, offset, minOf(length, 1))
            }
        val output = byteArrayOf(9, 9, 9, 9, 9)
        assertEquals(3, AndroidInputStreams.readNBytes(input, output, 1, 4))
        assertArrayEquals(byteArrayOf(9, 1, 2, 3, 9), output)
    }

    @Test fun zeroProgressDoesNotSpinOrLoseBytes() {
        val input =
            object : ByteArrayInputStream(byteArrayOf(4, 5)) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertArrayEquals(byteArrayOf(4, 5), AndroidInputStreams.readAllBytes(input))
    }

    @Test fun lengthAndBoundsAreCheckedWithoutReading() {
        val input = ByteArrayInputStream(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { AndroidInputStreams.readNBytes(input, -1) }
        assertThrows(IndexOutOfBoundsException::class.java) {
            AndroidInputStreams.readNBytes(input, ByteArray(1), Int.MAX_VALUE, 1)
        }
        assertArrayEquals(byteArrayOf(), AndroidInputStreams.readNBytes(input, 0))
        assertEquals(1, input.available())
    }

    @Test fun ioFailureIsPropagatedAndStreamRemainsCallerOwned() {
        val failure = IOException("fixture")
        val input =
            object : InputStream() {
                override fun read(): Int = throw failure

                override fun close(): Unit = error("callee must not close the stream")
            }
        assertEquals(failure, assertThrows(IOException::class.java) { AndroidInputStreams.readAllBytes(input) })
    }
}
