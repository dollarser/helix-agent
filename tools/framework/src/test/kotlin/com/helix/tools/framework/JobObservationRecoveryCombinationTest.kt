package com.helix.tools.framework

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JobObservationRecoveryCombinationTest {
    @Test
    fun stoppedAndRevokedObserversDoNotFreeBlockedQueryOrDiscloseItsLateResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a", true)
                values["b"] = value("b", true)
                queryAction = { binding ->
                    entered.countDown()
                    release.awaitChecked()
                    values.getValue(binding.handle)
                }
            }
        val limits = JobObservationLimits(maxQueries = 1, maxWaitMillis = 2_000, tickMillis = 5)
        fixture.service(limits).use { service ->
            try {
                val stopped = service.submit(fixture.call())
                entered.awaitChecked()
                val revoked = service.submit(fixture.call(id = "revoked"), allowed::get)
                allowed.set(false)
                assertTrue(service.stopWaiting("s", "observer"))
                assertTrue(publish(stopped) is ToolExecutorResult.CancelledWithEffectTruth)
                assertEquals(1, service.outstandingQueries())
                val busy = publish(service.submit(fixture.call(id = "other", handles = listOf("b"))))
                assertEquals(
                    "OBSERVATION_BUSY",
                    (busy as ToolExecutorResult.Completed)
                        .output.jsonObject
                        .getValue("reason")
                        .jsonPrimitive.content,
                )
                release.countDown()
                assertTrue(publish(revoked) is ToolExecutorResult.CancelledWithEffectTruth)
                assertEquals(0, service.activeCount())
                assertEquals(1, fixture.calls.get())
                assertTrue(publish(service.submit(fixture.call(id = "fresh"))) is ToolExecutorResult.Completed)
                assertEquals(2, fixture.calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun reopenedObserverDoesNotAcceptARevokedOriginalBindingOrReplayExecution() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        fixture.service().use { service ->
            assertTrue(publish(service.submit(fixture.call())) is ToolExecutorResult.Completed)
        }
        fixture.current = false
        fixture.service().use { reopened ->
            assertTrue(publish(reopened.submit(fixture.call())) is ToolExecutorResult.Failed)
            assertEquals(1, fixture.calls.get())
            assertEquals(0, reopened.outstandingQueries())
        }
    }

    private fun publish(submission: JobObservationSubmission): ToolExecutorResult =
        JobObservationPublication
            .settle(submission) { result, failure ->
                if (failure != null) throw failure
                requireNotNull(result)
            }.get(5, TimeUnit.SECONDS)
}
