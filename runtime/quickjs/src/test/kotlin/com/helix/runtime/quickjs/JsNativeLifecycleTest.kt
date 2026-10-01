package com.helix.runtime.quickjs

import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JsNativeLifecycleTest {
    @Test fun stopDoesNotNeedAnExecutionReplyOrBinderWorker() {
        val terminated = CountDownLatch(1)
        val calls = AtomicInteger()
        val watchdog =
            JsNativeWatchdog(60_000) {
                calls.incrementAndGet()
                terminated.countDown()
            }
        watchdog.requestStop()
        watchdog.start()
        assertTrue(terminated.await(2, TimeUnit.SECONDS))
        watchdog.requestStop()
        assertEquals(1, calls.get())
    }

    @Test fun pastExecutionDeadlineCannotBeExtendedByALaterRequest() {
        val terminated = CountDownLatch(1)
        val watchdog = JsNativeWatchdog(60_000) { terminated.countDown() }
        watchdog.shortenTo(System.nanoTime() - 1)
        watchdog.shortenTo(System.nanoTime() + TimeUnit.MINUTES.toNanos(5))
        watchdog.start()
        try {
            assertTrue(terminated.await(2, TimeUnit.SECONDS))
        } finally {
            watchdog.requestStop()
        }
    }

    @Test fun deadlineAloneTerminatesWithoutStopDelivery() {
        val terminated = CountDownLatch(1)
        val watchdog = JsNativeWatchdog(25) { terminated.countDown() }
        watchdog.start()
        try {
            assertTrue(terminated.await(2, TimeUnit.SECONDS))
        } finally {
            watchdog.requestStop()
        }
    }

    @Test fun watchdogRejectsDuplicateWorkersAndInvalidLifetime() {
        assertThrows(IllegalArgumentException::class.java) { JsNativeWatchdog(0) {} }
        assertThrows(IllegalArgumentException::class.java) { JsNativeWatchdog(60_001) {} }
        val terminated = CountDownLatch(1)
        val watchdog = JsNativeWatchdog(60_000) { terminated.countDown() }
        watchdog.start()
        try {
            assertThrows(IllegalStateException::class.java) { watchdog.start() }
        } finally {
            watchdog.requestStop()
        }
        assertTrue(terminated.await(2, TimeUnit.SECONDS))
    }

    @Test fun interruptedCleanupKeepsRealEffectPermitUntilOriginalDeath() {
        val death = JsProcessDeath()
        val ownership = ExecutionOwnership(EmptyStore())
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val interruptRestored = AtomicBoolean()
        val worker =
            Thread {
                requireNotNull(ownership.acquire("native-call")).use {
                    Thread.currentThread().interrupt()
                    entered.countDown()
                    death.awaitObserved()
                    interruptRestored.set(Thread.currentThread().isInterrupted)
                }
                returned.countDown()
            }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(death.isObserved())
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
            requireNotNull(ownership.acquire("writer-before-death")).close()
            worker.interrupt()
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
            requireNotNull(ownership.acquire("writer-after-cancel")).close()
            death.record()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(interruptRestored.get())
            requireNotNull(ownership.acquire("writer-after-death")).close()
        } finally {
            death.record()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
    }

    @Test fun failedStopPreservesBothEffectOwnershipAndTheOriginalFailure() {
        val death = JsProcessDeath()
        val ownership = ExecutionOwnership(EmptyStore())
        val attempted = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val original = AssertionError("synthetic control failure")
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
        val worker =
            Thread {
                failure.set(
                    runCatching {
                        requireNotNull(ownership.acquire("native-call")).use {
                            death.stopAndAwait {
                                attempted.countDown()
                                throw original
                            }
                        }
                    }.exceptionOrNull(),
                )
                returned.countDown()
            }
        worker.start()
        try {
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
            requireNotNull(ownership.acquire("writer-before-death")).close()
            death.record()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            org.junit.Assert.assertSame(original, failure.get())
            requireNotNull(ownership.acquire("writer-after-death")).close()
        } finally {
            death.record()
            worker.join(2000)
        }
        assertFalse(worker.isAlive)
    }

    @Test fun deathAlreadyObservedDoesNotClearCallerInterruption() {
        val death = JsProcessDeath()
        death.record()
        death.record()
        Thread.currentThread().interrupt()
        try {
            death.awaitObserved()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private class EmptyStore : ExecutionOwnership.Store {
        override fun owners(): Set<ExecutionOwnership.Owner> = emptySet()

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean = error("No retained ownership is used by an ordinary synchronous execution")
    }
}
