package com.helix.runtime.proot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PtyInputConnectionTest {
    @Test fun detachKeepsAcceptedInputAndRevokesOnlyTheOriginalWriter() {
        val input = PtyInputConnection()
        val first = checkNotNull(input.attach())
        assertNull(input.attach())
        assertEquals(PtyInputConnection.Admission.ACCEPTED, input.offer(first, byteArrayOf(1)))
        assertTrue(input.detach(first))
        assertEquals(PtyInputConnection.Admission.DETACHED, input.offer(first, byteArrayOf(2)))
        val second = checkNotNull(input.attach())
        assertNotEquals(first, second)
        assertFalse(input.detach(first))
        assertEquals(PtyInputConnection.Admission.ACCEPTED, input.offer(second, byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(1), input.poll())
        assertArrayEquals(byteArrayOf(3), input.poll())
        assertNull(input.poll())
    }

    @Test fun foreignConnectionCannotSubmitAndCloseRevokesAllWriters() {
        val input = PtyInputConnection()
        val writer = checkNotNull(input.attach())
        val foreign = checkNotNull(PtyInputConnection().attach())
        assertEquals(PtyInputConnection.Admission.DETACHED, input.offer(foreign, byteArrayOf(9)))
        assertFalse(input.detach(foreign))
        assertEquals(PtyInputConnection.Admission.ACCEPTED, input.offer(writer, byteArrayOf(1, 2)))
        assertEquals(2, input.close())
        assertEquals(0, input.close())
        assertNull(input.attach())
        assertNull(input.poll())
        assertFalse(input.detach(writer))
        assertEquals(PtyInputConnection.Admission.CLOSED, input.offer(writer, byteArrayOf(3)))
    }

    @Test fun reconnectCannotEvadePendingByteLimitOrReorderAcceptedInput() {
        val input = PtyInputConnection()
        val writer = checkNotNull(input.attach())
        repeat(4) { index ->
            assertEquals(PtyInputConnection.Admission.ACCEPTED, input.offer(writer, ByteArray(8192) { index.toByte() }))
        }
        assertTrue(input.detach(writer))
        val successor = checkNotNull(input.attach())
        assertEquals(PtyInputConnection.Admission.FULL, input.offer(successor, byteArrayOf(8)))
        assertArrayEquals(ByteArray(8192), input.poll())
        assertEquals(PtyInputConnection.Admission.ACCEPTED, input.offer(successor, byteArrayOf(8)))
        repeat(3) { index -> assertArrayEquals(ByteArray(8192) { (index + 1).toByte() }, input.poll()) }
        assertArrayEquals(byteArrayOf(8), input.poll())
        assertNull(input.poll())
    }

    @Test(timeout = 10_000)
    fun concurrentAttachHasOneWinnerAndRevokedSubmissionsNeverEnterQueue() {
        val input = PtyInputConnection()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val attempts =
                (1..8).map {
                    pool.submit<String?> {
                        start.await()
                        input.attach()
                    }
                }
            start.countDown()
            val winners = attempts.mapNotNull { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            val old = winners.single()
            assertTrue(input.detach(old))
            val current = checkNotNull(input.attach())
            val staleWrites =
                (1..8).map {
                    pool.submit<PtyInputConnection.Admission> {
                        input.offer(
                            old,
                            byteArrayOf(9),
                        )
                    }
                }
            staleWrites.forEach { assertEquals(PtyInputConnection.Admission.DETACHED, it.get(2, TimeUnit.SECONDS)) }
            assertEquals(PtyInputConnection.Admission.ACCEPTED, input.offer(current, byteArrayOf(1)))
            assertArrayEquals(byteArrayOf(1), input.poll())
            assertNull(input.poll())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}
