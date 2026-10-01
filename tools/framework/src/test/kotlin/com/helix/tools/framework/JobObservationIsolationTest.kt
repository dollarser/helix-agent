package com.helix.tools.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JobObservationIsolationTest {
    @Test
    fun cancellingTheInitiatorDoesNotRevokeASharedPhysicalResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a", true)
                queryAction = { binding ->
                    entered.countDown()
                    release.awaitChecked()
                    values.getValue(binding.handle)
                }
            }
        JobQueryLane(fixture, 1, System::nanoTime).use { lane ->
            try {
                val first = requireNotNull(lane.acquire(fixture.binding("a"), allowed::get))
                entered.awaitChecked()
                val second = requireNotNull(lane.acquire(fixture.binding("a")) { true })
                assertSame(first, second)
                allowed.set(false)
                release.countDown()
                assertTrue(second.result.get(5, TimeUnit.SECONDS) is JobQueryLane.QueryResult.Value)
                assertEquals(1, fixture.calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun originalBindingIsRevalidatedAfterCompletionBeforePublication() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        fixture.service().use { service ->
            val submission = service.submit(fixture.call())
            assertTrue(submission.result.get(5, TimeUnit.SECONDS) is ToolExecutorResult.Completed)
            fixture.current = false
            val published = publish(submission)
            assertTrue(published is ToolExecutorResult.CancelledWithEffectTruth)
            assertEquals(0, service.activeCount())
        }
    }

    @Test
    fun eachSubscriberChecksItsOwnPermissionBeforeDisclosure() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        val allowed = AtomicBoolean(true)
        fixture.service().use { service ->
            val revoked = service.submit(fixture.call(), allowed::get)
            val retained = service.submit(fixture.call(id = "retained"))
            assertTrue(revoked.result.get(5, TimeUnit.SECONDS) is ToolExecutorResult.Completed)
            assertTrue(retained.result.get(5, TimeUnit.SECONDS) is ToolExecutorResult.Completed)
            allowed.set(false)
            assertTrue(publish(revoked) is ToolExecutorResult.CancelledWithEffectTruth)
            assertTrue(publish(retained) is ToolExecutorResult.Completed)
            assertEquals(0, service.activeCount())
        }
    }

    @Test
    fun failingPublicationRevalidationReachesFinalizerAndReleasesQuota() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        val fail = AtomicBoolean(false)
        fixture.service().use { service ->
            val submission =
                service.submit(fixture.call()) {
                    check(!fail.get()) { "fixture revalidation failure" }
                    true
                }
            assertTrue(submission.result.get(5, TimeUnit.SECONDS) is ToolExecutorResult.Completed)
            fail.set(true)
            val result: ToolExecutorResult =
                JobObservationPublication
                    .settle(submission) { value, failure ->
                        assertEquals(null, value)
                        assertTrue(failure is IllegalStateException)
                        ToolExecutorResult.Failed("validation failed", sideEffectFree = true)
                    }.get(5, TimeUnit.SECONDS)
            assertFalse(result is ToolExecutorResult.Completed)
            assertEquals(0, service.activeCount())
        }
    }

    private fun publish(submission: JobObservationSubmission): ToolExecutorResult =
        JobObservationPublication
            .settle(submission) { result, failure ->
                if (failure != null) throw failure
                requireNotNull(result)
            }.get(5, TimeUnit.SECONDS)
}
