package com.helix.app.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalInputQueueTest {
    @Test fun fragmentedKeyboardBurstIsCoalescedAndOrderedAcrossRingWrap() {
        val queue = TerminalInputQueue()
        val expected = "中文-shell-input\n".repeat(3000).toByteArray()
        val actual = ByteArrayOutputStream()
        expected.forEachIndexed { index, byte ->
            assertTrue(queue.offer(byteArrayOf(byte)))
            if (index % 9000 == 8999) {
                actual.write(checkNotNull(queue.poll()))
            }
        }
        var batch = queue.poll()
        while (batch != null) {
            assertTrue(batch.size <= TerminalInputQueue.MAX_CHUNK)
            actual.write(batch)
            batch = queue.poll()
        }
        assertArrayEquals(expected, actual.toByteArray())
    }

    @Test fun capacityAndLargePasteRejectWholeInputAndCloseDiscardsWithoutReplay() {
        val queue = TerminalInputQueue()
        val batch = ByteArray(TerminalInputQueue.MAX_CHUNK) { 7 }
        assertFalse(queue.offer(ByteArray(batch.size + 1)))
        repeat(4) { assertTrue(queue.offer(batch)) }
        batch.fill(9)
        assertFalse(queue.offer(byteArrayOf(3)))
        assertArrayEquals(ByteArray(batch.size) { 7 }, queue.poll())
        queue.close()
        assertNull(queue.poll())
        assertFalse(queue.offer(byteArrayOf(3)))
    }
}
