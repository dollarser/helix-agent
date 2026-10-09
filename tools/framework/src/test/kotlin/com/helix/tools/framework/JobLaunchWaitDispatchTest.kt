package com.helix.tools.framework

import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JobLaunchWaitDispatchTest {
    @Test fun launchOnceWaitsForSameHandleAndAuditsAsExecution() {
        JobObservationDispatchFixture().use { fixture ->
            val launched = AtomicInteger()
            fixture.observations.values["a"] = fixture.observations.value("a", true)
            fixture.register(executor(fixture, launched))
            val outcome = fixture.dispatcher.dispatchCompletion(fixture.call("a")).get(5, TimeUnit.SECONDS)
            assertTrue(outcome is ToolDispatchOutcome.Succeeded)
            assertEquals(1, launched.get())
            assertEquals(1, fixture.events.size)
            assertFalse(fixture.events.single().jobObservation)
            assertEquals(0, fixture.service.activeCount())
        }
    }

    @Test fun pendingLaunchReleasesWorkerAndKeepsOriginalBatchOrder() {
        JobObservationDispatchFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.observations.queryAction = { binding ->
                entered.countDown()
                release.awaitChecked()
                fixture.observations.value(binding.handle, true)
            }
            val launches = AtomicInteger()
            fixture.register(executor(fixture, launches))
            fixture.register(
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        entered.awaitChecked()
                        release.countDown()
                        return ToolExecutorResult.Completed(buildJsonObject {})
                    }
                },
                fixture.descriptor("fixture.next"),
            )
            try {
                val requests = listOf(fixture.call("a"), fixture.call("next", "fixture.next"))
                val batch =
                    CompletableFuture
                        .supplyAsync {
                            fixture.scheduler.scheduleBatch(requests)
                        }.get(5, TimeUnit.SECONDS)
                assertTrue(batch.outcomes.all { it is ToolDispatchOutcome.Succeeded })
                assertEquals(
                    listOf("a", "next"),
                    fixture.events.sortedBy { it.correlationId }.map { it.correlationId },
                )
                assertEquals(1, launches.get())
                assertEquals(0, fixture.service.activeCount())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun unknownLaunchDoesNotQueryCollectOrRetry() {
        JobObservationDispatchFixture().use { fixture ->
            val launches = AtomicInteger()
            fixture.register(
                fixture.service.launchWaitExecutor(
                    object : ToolExecutor {
                        override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                            launches.incrementAndGet()
                            return ToolExecutorResult.Failed("SUBMISSION_UNKNOWN", false, true)
                        }
                    },
                    { _, _ -> error("Unknown launch must not wait") },
                    { _, _, _ -> error("Unknown launch must not collect") },
                ),
            )
            val outcome = fixture.dispatcher.dispatchCompletion(fixture.call("a")).get(5, TimeUnit.SECONDS)
            assertTrue(outcome is ToolDispatchOutcome.ExecutionFailed)
            assertEquals(1, launches.get())
            assertEquals(0, fixture.observations.calls.get())
        }
    }

    @Test fun finalBindingChangeRejectsLaunchResultAndReleasesObservationQuota() {
        JobObservationDispatchFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.observations.values["a"] = fixture.observations.value("a", true)
            fixture.register(
                fixture.service.launchWaitExecutor(
                    object : ToolExecutor {
                        override fun execute(call: ExecutableToolCall) =
                            ToolExecutorResult.Completed(buildJsonObject { put("originalCallId", "a") })
                    },
                    { _, _ -> 500 },
                    { _, launched, _ ->
                        entered.countDown()
                        release.awaitChecked()
                        launched
                    },
                ),
            )
            val result = fixture.dispatcher.dispatchCompletion(fixture.call("a"))
            try {
                entered.awaitChecked()
                fixture.observations.current = false
                release.countDown()
                val failure = result.get(5, TimeUnit.SECONDS) as ToolDispatchOutcome.ExecutionFailed
                assertFalse(failure.sideEffectFree)
                assertTrue(failure.requiresReview)
                assertEquals(1, fixture.events.size)
                assertEquals(0, fixture.service.activeCount())
            } finally {
                release.countDown()
            }
        }
    }

    private fun executor(
        fixture: JobObservationDispatchFixture,
        launches: AtomicInteger,
    ): ToolExecutor =
        fixture.service.launchWaitExecutor(
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    launches.incrementAndGet()
                    return ToolExecutorResult.Completed(buildJsonObject { put("originalCallId", call.toolCallId) })
                }
            },
            { _, _ -> 500 },
            { _, _, observed -> observed },
        )
}
