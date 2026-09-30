package com.helix.app.localmodel

import com.helix.provider.api.local.LocalRuntimeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalRuntimeCallsTest {
    @Test fun blockedIoDoesNotBlockControlOrGrowAQueue() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            LocalRuntimeCalls("test-model-io", 1).use { io ->
                LocalRuntimeCalls("test-model-control", 1).use { control ->
                    try {
                        assertNull(
                            withTimeoutOrNull(150) {
                                io.call {
                                    entered.countDown()
                                    release.await(3, TimeUnit.SECONDS)
                                    1
                                }
                            },
                        )
                        assertTrue(entered.await(1, TimeUnit.SECONDS))
                        assertEquals(2, withTimeout(1000) { control.call { 2 } })
                        var refused = false
                        try {
                            io.call { error("Saturated lane must not enqueue another RPC") }
                        } catch (
                            _: LocalRuntimeException,
                        ) {
                            refused = true
                        }
                        assertTrue(refused)
                    } finally {
                        release.countDown()
                    }
                }
            }
        }

    @Test fun workerFailureReachesCallerAndCancellationHookDoesNotNeedWorker() =
        runBlocking {
            LocalRuntimeCalls("test-model-failure", 1).use { calls ->
                val failed = runCatching { calls.call { throw IllegalStateException("fixture") } }
                assertTrue(failed.exceptionOrNull() is IllegalStateException)
            }
            LocalRuntimeCalls("test-model-cancellation", 1).use { calls ->
                val started = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val cancelled = CountDownLatch(1)
                try {
                    val receipt =
                        async {
                            calls.call(onCancel = { cancelled.countDown() }) {
                                started.complete(Unit)
                                release.await(3, TimeUnit.SECONDS)
                            }
                        }
                    started.await()
                    receipt.cancel()
                    assertTrue(cancelled.await(1, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        }
}
