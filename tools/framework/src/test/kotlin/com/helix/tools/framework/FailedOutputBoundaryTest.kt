package com.helix.tools.framework

import com.helix.core.model.CancelSignal
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class FailedOutputBoundaryTest {
    @Test fun cancellationDoesNotPublishLateFailureOutput() = lateOutput(cancel = true)

    @Test fun timeoutDoesNotPublishLateFailureOutput() = lateOutput(cancel = false)

    @Test fun revokedBindingSuppressesReceivedFailureOutputAtPublication() {
        JobObservationDispatchFixture().use { fixture ->
            val calls = AtomicInteger()
            val launch =
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        calls.incrementAndGet()
                        fixture.registry.replaceOwner(bindingOwner(ToolOrigin.BuiltInOrigin), emptyList())
                        return failureOutput()
                    }
                }
            fixture.register(
                fixture.service.launchWaitExecutor(
                    launch,
                    waitMillis = { _, _ -> error("Failed launch must not begin observation") },
                    finish = { _, _, _ -> error("Failed launch must not enter completion callback") },
                ),
            )
            val outcome =
                fixture.dispatcher.dispatchCompletion(fixture.call()).get(5, TimeUnit.SECONDS)
                    as ToolDispatchOutcome.ExecutionFailed
            assertEquals(DispatchOutcomeCode.CANCELLED_AFTER_START, outcome.code)
            assertTrue(outcome.detail.contains("JOB_OBSERVATION_REVOKED"))
            assertTrue(outcome.requiresReview)
            assertFalse(outcome.sideEffectFree)
            assertNull(outcome.result)
            assertNull(fixture.events.single().outputHash)
            assertEquals(1, calls.get())
            assertEquals(0, fixture.observations.calls.get())
        }
    }

    private fun lateOutput(cancel: Boolean) {
        JobObservationDispatchFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val cancelled = AtomicBoolean()
            val calls = AtomicInteger()
            fixture.register(
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        calls.incrementAndGet()
                        entered.countDown()
                        try {
                            awaitIgnoringInterrupt(release)
                            return failureOutput()
                        } finally {
                            exited.countDown()
                        }
                    }
                },
                fixture.descriptor().copy(timeout = (if (cancel) 5_000 else 400).milliseconds),
            )
            val request =
                fixture.call().copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = cancelled.get()
                        },
                )
            val pending = CompletableFuture.supplyAsync { fixture.dispatcher.dispatch(request) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (cancel) cancelled.set(true)
                val outcome = pending.get(5, TimeUnit.SECONDS) as ToolDispatchOutcome.ExecutionFailed
                val code = if (cancel) DispatchOutcomeCode.CANCELLED_AFTER_START else DispatchOutcomeCode.TIMEOUT
                assertEquals(code, outcome.code)
                assertTrue(outcome.requiresReview)
                assertFalse(outcome.sideEffectFree)
                assertNull(outcome.result)
                assertEquals(1L, exited.count)
                release.countDown()
                assertTrue(exited.await(5, TimeUnit.SECONDS))
                assertEquals(outcome, pending.get(5, TimeUnit.SECONDS))
                assertEquals(code, fixture.events.single().code)
                assertNull(fixture.events.single().outputHash)
                assertEquals(1, calls.get())
            } finally {
                release.countDown()
                assertTrue(exited.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Suppress("SwallowedException") // Simulate a remote executor that returns only after local cancellation.
    private fun awaitIgnoringInterrupt(release: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (release.count > 0 && System.nanoTime() < deadline) {
            try {
                release.await(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // The caller must settle without pretending this physical execution exited.
            }
        }
        check(release.count == 0L) { "Test failed to release executor" }
    }

    private fun failureOutput() =
        ToolExecutorResult.FailedWithOutput(
            buildJsonObject { put("privateFailureOutput", "must not be disclosed after revocation") },
        )
}
