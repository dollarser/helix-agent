package com.helix.tools.framework

import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ToolExecutionCapacityTest {
    @Test fun cancelledButLiveWorkerKeepsCapacityWhileControlRemainsAvailable() = checkCapacity(false)

    @Test fun timedOutButLiveWorkerKeepsCapacityWhileControlRemainsAvailable() = checkCapacity(true)

    @Suppress("LongMethod", "SwallowedException") // Explicitly release the non-interruptible fixture.
    private fun checkCapacity(timeout: Boolean) {
        val activity = ToolExecutionActivity()
        val workers = ToolExecutionPools.bounded(1, "test-worker")
        val controls = ToolExecutionPools.bounded(1, "test-control")
        val callers = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.EPOCH
            }
        val call =
            ExecutableToolCall(
                "call",
                "read",
                "1",
                buildJsonObject {},
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.EPOCH.plusMillis(if (timeout) 200 else 30_000),
                object : CancelSignal {
                    override fun isCancelled() = cancelled.get()
                },
            )
        val blocked =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // Deliberately still running.
                        }
                    }
                    return ToolExecutorResult.Completed(buildJsonObject {})
                }
            }
        val harmless =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall) = ToolExecutorResult.Completed(buildJsonObject {})
            }
        try {
            val first =
                callers.submit<ToolExecutorResult?> {
                    ToolDeadlineRunner(clock, workers, activity).executeWithinDeadline(blocked, call)
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (!timeout) cancelled.set(true)
            assertEquals(
                if (timeout) ToolExecutorResult.TimedOut else ToolExecutorResult.Cancelled,
                first.get(5, TimeUnit.SECONDS),
            )
            cancelled.set(false)
            repeat(10) {
                val rejected = ToolDeadlineRunner(clock, workers, activity).executeWithinDeadline(harmless, call)
                assertTrue(rejected is ToolExecutorResult.Failed && rejected.sideEffectFree)
            }
            assertEquals(ToolExecutionActivity.Snapshot(1, 1), activity.snapshot())
            assertEquals(1, workers.poolSize)
            assertEquals(1, workers.activeCount)
            assertEquals(0, workers.queue.size)
            assertTrue(
                ToolDeadlineRunner(
                    clock,
                    controls,
                ).executeWithinDeadline(harmless, call) is ToolExecutorResult.Completed,
            )
        } finally {
            release.countDown()
            workers.shutdown()
            controls.shutdown()
            callers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(controls.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(ToolExecutionActivity.Snapshot(0, 0), activity.snapshot())
        }
    }
}
