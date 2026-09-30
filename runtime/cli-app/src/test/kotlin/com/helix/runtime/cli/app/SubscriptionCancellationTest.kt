package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionCancellationTest {
    @Test fun lateResourceIsClosedWithoutExecutingAndNormalResourcesCloseOnce() {
        val stop = SubscriptionCancellation()
        val closes = AtomicInteger()
        stop.cancel()
        assertThrows(CancellationException::class.java) {
            stop.using(Closeable { closes.incrementAndGet() }) { error("must not execute") }
        }
        assertEquals(1, closes.get())
        val fresh = SubscriptionCancellation()
        assertEquals("ok", fresh.using(Closeable { closes.incrementAndGet() }) { "ok" })
        fresh.cancel()
        assertEquals(2, closes.get())
    }

    @Test fun stopRemainsEffectiveThroughBodyConsumptionAndDoesNotAffectNextJob() {
        val stop = SubscriptionCancellation()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val worker =
            Thread {
                stop.using(
                    Closeable {
                        closes.incrementAndGet()
                        released.countDown()
                    },
                ) {
                    entered.countDown()
                    check(released.await(3, TimeUnit.SECONDS))
                }
            }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stop.cancel()
            stop.cancel()
            worker.join(3000)
            assertEquals(1, closes.get())
            assertEquals("next", SubscriptionCancellation().using(Closeable {}) { "next" })
        } finally {
            released.countDown()
            worker.join(3000)
        }
    }
}
