package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CliReplayCallsTest {
    @Test fun ordinaryCallReturnsItsActualValue() {
        CliReplayCalls().use { assertEquals("result", it.call { "result" }) }
    }

    @Test fun timedOutNonCooperativeCallKeepsTheOnlyPhysicalSlot() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        CliReplayCalls().use { calls ->
            try {
                assertThrows(TimeoutException::class.java) {
                    calls.call(timeoutMillis = 100) {
                        entered.countDown()
                        try {
                            while (release.count > 0) {
                                try {
                                    release.await()
                                } catch (
                                    _: InterruptedException,
                                ) {
                                    // Simulate blocked Binder.
                                }
                            }
                            "late result"
                        } finally {
                            exited.countDown()
                        }
                    }
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertThrows(RejectedExecutionException::class.java) { calls.call { error("must not run") } }
            } finally {
                release.countDown()
                assertTrue(exited.await(2, TimeUnit.SECONDS))
            }
        }
    }
}
