package com.helix.runtime.proot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PtyBuffersTest {
    @Test fun multibyteAndEscapeSequencesStayByteExactAcrossPagesAndPhysicalWrap() {
        val output = PtyOutputBuffer("session", "generation", 17)
        val source = "\u001b[32m中文\u001b[0m!\r\n".toByteArray()
        var cursor: String? = null
        val received = ByteArrayOutputStream()
        // Drain every append so physical ring wrap does not imply data loss.
        source.forEach { byte ->
            output.append(byteArrayOf(byte))
            val page = output.read(cursor, 1)
            assertFalse(page.gapBefore)
            received.write(page.bytes)
            cursor = page.cursor
        }
        assertArrayEquals(source, received.toByteArray())
        assertFalse(output.read(cursor).eof)
        output.finish()
        assertTrue(output.read(cursor).eof)
    }

    @Test fun detachedReaderGetsRecentTailAndExplicitGapWithoutBlockingProducer() {
        val output = PtyOutputBuffer("session", "generation", 13)
        val chunk = ByteArray(101) { it.toByte() }
        repeat(1000) { output.append(chunk) }
        output.finish()
        val page = output.read(null, 8)
        assertTrue(page.gapBefore)
        assertFalse(page.eof)
        assertArrayEquals(chunk.copyOfRange(88, 96), page.bytes)
        val last = output.read(page.cursor)
        assertFalse(last.gapBefore)
        assertTrue(last.eof)
        assertArrayEquals(chunk.copyOfRange(96, 101), last.bytes)
        assertEquals("session:generation:101000", last.cursor)
    }

    @Test fun cursorRetriesAreStableUntilEvictionAndForeignFutureMalformedCursorsFail() {
        val output = PtyOutputBuffer("session", "one", 8)
        output.append("abc".toByteArray())
        val cursor = output.read(null, 1).cursor
        assertArrayEquals(output.read(cursor).bytes, output.read(cursor).bytes)
        val other = PtyOutputBuffer("session", "two")
        assertThrows(IllegalArgumentException::class.java) { other.read(cursor) }
        listOf("session:one:4", "session:one:-1", "session:one:01", "session:one:+1", "other:one:0").forEach {
            assertThrows(IllegalArgumentException::class.java) { output.read(it) }
        }
        output.append("0123456789".toByteArray())
        assertTrue(output.read(cursor).gapBefore)
        assertArrayEquals("23456789".toByteArray(), output.read(cursor).bytes)
    }

    @Test fun outputCopiesBothDirectionsAndRejectsWritesAfterDrainedEof() {
        val output = PtyOutputBuffer("session", "generation")
        val bytes = byteArrayOf(1, 2, 3)
        output.append(bytes)
        bytes.fill(9)
        output.read().bytes.fill(8)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.read().bytes)
        output.finish()
        output.finish()
        assertTrue(output.read().eof)
        assertThrows(IllegalStateException::class.java) { output.append(byteArrayOf(4)) }
        assertThrows(IllegalArgumentException::class.java) { output.read(maxBytes = 8193) }
        assertThrows(IllegalArgumentException::class.java) { PtyOutputBuffer("bad:id", "gen") }
        assertThrows(IllegalArgumentException::class.java) { PtyOutputBuffer("id", "gen", 0) }
    }

    @Test(timeout = 10_000)
    fun concurrentDrainAndReadPreserveOffsetsEvenWhenReaderLosesHistory() {
        val output = PtyOutputBuffer("session", "generation", 4096)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val producer =
                worker.submit {
                    repeat(1000) { batch ->
                        output.append(ByteArray(256) { ((batch * 256 + it) % 251).toByte() })
                    }
                    output.finish()
                }
            var cursor: String? = null
            var previous = 0L
            do {
                val page = output.read(cursor, 317)
                val next = page.cursor.substringAfterLast(':').toLong()
                val start = next - page.bytes.size
                assertEquals(start > previous, page.gapBefore)
                assertArrayEquals(ByteArray(page.bytes.size) { ((start + it) % 251).toByte() }, page.bytes)
                previous = next
                cursor = page.cursor
                Thread.yield()
            } while (!page.eof)
            producer.get(1, TimeUnit.SECONDS)
            assertEquals(256000L, previous)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun byteBudgetRejectsWholePasteAndRecoversSpaceInOrder() {
        val input = PtyInputBuffer()
        val chunk = ByteArray(8192) { 1 }
        repeat(4) { assertEquals(PtyInputBuffer.Admission.ACCEPTED, input.offer(chunk)) }
        assertEquals(PtyInputBuffer.Admission.FULL, input.offer(byteArrayOf(2)))
        chunk.fill(3)
        assertTrue(input.poll()!!.all { it == 1.toByte() })
        assertEquals(PtyInputBuffer.Admission.ACCEPTED, input.offer(byteArrayOf(4)))
        repeat(3) { assertTrue(input.poll()!!.all { it == 1.toByte() }) }
        assertArrayEquals(byteArrayOf(4), input.poll())
        assertNull(input.poll())
    }

    @Test fun tinyKeyChunksAreAlsoBoundedAndCloseReportsDiscardedPendingBytes() {
        val input = PtyInputBuffer()
        repeat(64) { assertEquals(PtyInputBuffer.Admission.ACCEPTED, input.offer(byteArrayOf(1))) }
        assertEquals(PtyInputBuffer.Admission.FULL, input.offer(byteArrayOf(2)))
        assertEquals(64, input.close())
        assertEquals(0, input.close())
        assertNull(input.poll())
        assertEquals(PtyInputBuffer.Admission.CLOSED, input.offer(byteArrayOf(3)))
        assertThrows(IllegalArgumentException::class.java) { input.offer(ByteArray(8193)) }
        assertThrows(IllegalArgumentException::class.java) { input.offer(byteArrayOf()) }
    }
}
