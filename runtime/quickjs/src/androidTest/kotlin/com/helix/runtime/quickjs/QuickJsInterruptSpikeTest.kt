package com.helix.runtime.quickjs

import app.cash.zipline.InterruptHandler
import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.atomic.AtomicLong

/**
 * HXA-050 spike capability 3 (roadmap M5 + doc 03 §10): `InterruptHandler` halts an
 * infinite `while(true){}` loop; the instance stays usable and closeable.
 *
 * Probe observations (API 29 + API 36 arm64-v8a): both trigger styles fail with
 * `app.cash.zipline.QuickJsException: interrupted`; the poll-count handler fired on
 * poll 1001. Pinned here. The class-level [Timeout] rule is the anti-hang guard: if
 * interruption ever stops working the whole test class fails after 120 s instead of
 * hanging the device runner forever.
 */
class QuickJsInterruptSpikeTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(120)

    private lateinit var quickJs: QuickJs

    @Before
    fun createInstance() {
        quickJs = QuickJs.create()
    }

    @After
    fun closeInstance() {
        quickJs.close()
    }

    @Test
    fun pollCountInterruptHaltsInfiniteLoop() {
        val polls = AtomicLong(0)
        quickJs.interruptHandler =
            InterruptHandler {
                if (polls.incrementAndGet() > 1000L) {
                    true
                } else {
                    false
                }
            }
        assertInterrupted("while (true) {}")
        assertTrue("handler must have been polled, polls=${polls.get()}", polls.get() > 1000L)
        // Probe observation: the SAME instance keeps working after an interruption.
        assertEquals(2, quickJs.evaluate("1 + 1"))
    }

    @Test
    fun monotonicDeadlineInterruptHaltsInfiniteLoop() {
        // doc 03 §4: deadline checks use the monotonic clock.
        val deadline = System.nanoTime() + 300L * 1_000_000
        quickJs.interruptHandler = InterruptHandler { System.nanoTime() > deadline }
        assertInterrupted("while (true) {}")
        assertEquals(4, quickJs.evaluate("2 + 2"))
    }

    private fun assertInterrupted(source: String) {
        val error =
            runCatching { quickJs.evaluate(source) }.exceptionOrNull()
                ?: throw AssertionError("expected interruption error for: $source")
        assertTrue(
            "expected QuickJsException, got ${error.javaClass.name}",
            error is QuickJsException,
        )
        assertTrue(
            "expected 'interrupted' in the error, got: ${error.message?.take(160)}",
            error.message?.contains("interrupted") == true,
        )
    }
}
