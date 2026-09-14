package com.helix.runtime.proot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProotOutputCaptureTest {
    @Test fun eofIsRequiredForCompleteCapture() {
        val capture = BoundedCapture(OutputBudget(100))
        capture.drain(ByteArrayInputStream("ok".toByteArray()))
        capture.finish()
        assertTrue(capture.complete)
        assertEquals("ok", capture.bytes.decodeToString())
        val broken = BoundedCapture(OutputBudget(100))
        broken.drain(
            object : InputStream() {
                override fun read(): Int = throw IOException("fixture")
            },
        )
        broken.finish()
        assertFalse(broken.complete)
    }

    @Test fun latePumpCannotChangeThePublishedSnapshotOrClaimComplete() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val capture = BoundedCapture(OutputBudget(100))
        val thread =
            Thread {
                capture.drain(
                    object : InputStream() {
                        override fun read(): Int = -1

                        override fun read(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            reading.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            bytes[offset] = 65
                            return 1
                        }
                    },
                )
            }
        thread.start()
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            capture.finish()
            release.countDown()
            thread.join(5_000)
            assertFalse(thread.isAlive)
            assertFalse(capture.complete)
            assertEquals(0, capture.bytes.size)
        } finally {
            release.countDown()
            thread.join(5_000)
        }
    }
}
