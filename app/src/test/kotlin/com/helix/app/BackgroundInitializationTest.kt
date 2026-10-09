package com.helix.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundInitializationTest {
    @Test fun cancelledAsyncReaderDoesNotCancelSharedConstruction() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val value = Any()
            val initialization =
                BackgroundInitialization("test-async-container") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    value
                }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val reader = launch(start = CoroutineStart.UNDISPATCHED) { initialization.awaitAsync() }
                withTimeout(2_000) { reader.cancelAndJoin() }
                release.countDown()
                assertSame(value, withTimeout(2_000) { initialization.awaitAsync() })
            } finally {
                release.countDown()
            }
        }

    @Test fun asynchronousFailurePreservesOriginalCause() =
        runBlocking {
            val failure = IllegalStateException("async fixture")
            val initialization = BackgroundInitialization<Any>("test-async-failure") { throw failure }
            val observed = runCatching { initialization.awaitAsync() }.exceptionOrNull()
            assertEquals(failure.javaClass, observed?.javaClass)
            assertEquals(failure.message, observed?.message)
            // Coroutine stack recovery may copy the exception, but must retain the original cause.
            assertSame(failure, generateSequence(observed) { it.cause }.last())
        }

    @Test fun concurrentReadersShareOneBackgroundConstruction() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val value = Any()
        val initialization =
            BackgroundInitialization("test-container-init") {
                assertEquals("test-container-init", Thread.currentThread().name)
                calls.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                value
            }
        val readers = Executors.newFixedThreadPool(4)
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val results = (1..4).map { readers.submit<Any> { initialization.await() } }
            release.countDown()
            results.forEach { assertSame(value, it.get(5, TimeUnit.SECONDS)) }
            assertSame(value, initialization.await())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            readers.shutdownNow()
        }
    }

    @Test fun initializationFailureReachesEveryReaderWithoutRetry() {
        val failure = IllegalStateException("fixture failure")
        val calls = AtomicInteger()
        val initialization =
            BackgroundInitialization<Any>("test-container-failure") {
                calls.incrementAndGet()
                throw failure
            }
        repeat(2) {
            val observed = runCatching { initialization.await() }.exceptionOrNull()
            assertSame(failure, observed)
        }
        assertEquals(1, calls.get())
    }
}
