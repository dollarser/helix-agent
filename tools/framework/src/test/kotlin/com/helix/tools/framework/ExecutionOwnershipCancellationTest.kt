package com.helix.tools.framework

import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExecutionOwnershipCancellationTest {
    @Suppress("SwallowedException") // Deliberately emulate native IO that ignores interrupt until it actually exits.
    @Test
    fun cancellationCannotFreeAnExecutorThatHasNotExited() {
        val store =
            object : ExecutionOwnership.Store {
                override fun read(): ExecutionOwnership.Owner? = null

                override fun compareAndSet(
                    expected: ExecutionOwnership.Owner?,
                    replacement: ExecutionOwnership.Owner?,
                ): Boolean = error("ordinary execution must not persist a detached owner")
            }
        val gate = ExecutionOwnership(store)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val executor =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    entered.countDown()
                    while (release.count != 0L) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // Still executing: cancellation is a request, not an exit proof.
                        }
                    }
                    return ToolExecutorResult.Completed(buildJsonObject {})
                }
            }
        val workers = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        val now = Instant.parse("2026-09-18T00:00:00Z")
        val clock =
            object : Clock {
                override fun now(): Instant = now
            }
        val call =
            ExecutableToolCall(
                "call",
                "write",
                "1",
                buildJsonObject {},
                ExecutionTargetType.LOCAL_PROOT,
                now.plusSeconds(30),
                object : CancelSignal {
                    override fun isCancelled() = cancelled.get()
                },
            )
        try {
            val outcome =
                callers.submit<ToolExecutorResult?> {
                    ToolDeadlineRunner(clock, workers).executeWithinDeadline(gate.guard(executor), call)
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertEquals(ToolExecutorResult.Cancelled, outcome.get(5, TimeUnit.SECONDS))
            assertNull(gate.acquire("next-write"))
            assertNull(gate.acquire("next-read", exclusive = false))
        } finally {
            release.countDown()
            workers.shutdown()
            callers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
        requireNotNull(gate.acquire("after-real-exit")).close()
    }
}
