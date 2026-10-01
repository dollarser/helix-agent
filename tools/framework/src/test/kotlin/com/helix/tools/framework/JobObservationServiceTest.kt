package com.helix.tools.framework

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JobObservationServiceTest {
    @Test fun invalidLaterHandleRejectsBeforeAnyQuery() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        fixture.service().use { service ->
            service.submit(fixture.call(handles = listOf("a", "missing"))).use { submission ->
                assertTrue(submission.result.get(5, TimeUnit.SECONDS) is ToolExecutorResult.Failed)
                assertEquals(0, fixture.calls.get())
                assertEquals(0, service.activeCount())
            }
        }
    }

    @Test fun duplicateHandlesAreValidatedButOnlyQueriedOnce() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        fixture.service().use { service ->
            service.submit(fixture.call(handles = listOf("a", "a"))).use { submission ->
                val result = output(submission)
                assertEquals("CONDITION_MET", result.getValue("reason").jsonPrimitive.content)
                assertEquals(1, result.getValue("observations").jsonArray.size)
                assertEquals(2, fixture.resolutions.get())
                assertEquals(1, fixture.calls.get())
                // Result completion does not release the publication quota before durable settlement.
                assertEquals(1, service.activeCount())
            }
            assertEquals(0, service.activeCount())
        }
    }

    @Test fun anyCanFinishWhileAllRequiresEveryTerminal() {
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a", true)
                values["b"] = value("b")
            }
        fixture.service().use { service ->
            service.submit(fixture.call(handles = listOf("a", "b"))).use {
                assertEquals("CONDITION_MET", output(it).getValue("reason").jsonPrimitive.content)
            }
            service.submit(fixture.call(id = "all", handles = listOf("a", "b"), condition = "ALL")).use {
                assertEquals("WAIT_EXPIRED", output(it).getValue("reason").jsonPrimitive.content)
            }
        }
    }

    @Test fun reviewIsNotAClaimOfSuccessfulExecution() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", terminal = true, review = true) }
        fixture.service().use { service ->
            service.submit(fixture.call()).use {
                val output = output(it)
                assertEquals("REVIEW_REQUIRED", output.getValue("reason").jsonPrimitive.content)
                assertEquals(
                    "true",
                    output
                        .getValue(
                            "observations",
                        ).jsonArray
                        .single()
                        .jsonObject["settlementPending"]
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }
    }

    @Test fun outerDeadlineAndInnerWaitingBudgetRemainDifferent() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a") }
        fixture.service().use { service ->
            service.submit(fixture.call(outerMillis = 0)).use {
                val result = it.result.get(5, TimeUnit.SECONDS) as ToolExecutorResult.TimedOutWithEffectTruth
                assertTrue(result.sideEffectFree)
                assertFalse(result.requiresReview)
                assertEquals(0, fixture.calls.get())
            }
            service.submit(fixture.call(id = "inner", outerMillis = 200)).use {
                assertEquals("WAIT_EXPIRED", output(it).getValue("reason").jsonPrimitive.content)
                assertEquals(0, fixture.calls.get())
            }
        }
    }

    @Test fun stopWaitingIsScopedAndNeverChangesOriginalJob() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a")
                queryAction = { binding ->
                    entered.countDown()
                    release.awaitChecked()
                    values.getValue(binding.handle)
                }
            }
        fixture.service().use { service ->
            service.submit(fixture.call()).use {
                try {
                    entered.awaitChecked()
                    assertFalse(service.stopWaiting("different-session", "observer"))
                    assertTrue(service.stopWaiting("s", "observer"))
                    val result = it.result.get(5, TimeUnit.SECONDS) as ToolExecutorResult.CancelledWithEffectTruth
                    assertTrue(result.sideEffectFree)
                    assertFalse(result.requiresReview)
                    assertEquals("RUNNING", fixture.values.getValue("a").state)
                    assertEquals(1, service.outstandingQueries())
                    release.countDown()
                    assertEquals(result, it.result.get(5, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test fun queryTimeoutKeepsPhysicalCapacityUntilRealExit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a")
                values["b"] = value("b")
                queryAction = { binding ->
                    entered.countDown()
                    release.awaitChecked()
                    values.getValue(binding.handle)
                }
            }
        val limits =
            JobObservationLimits(maxQueries = 1, queryTimeoutMillis = 100, tickMillis = 5, maxWaitMillis = 2_000)
        fixture.service(limits).use { service ->
            try {
                service.submit(fixture.call()).use {
                    entered.awaitChecked()
                    assertEquals("SOURCE_UNAVAILABLE", output(it).getValue("reason").jsonPrimitive.content)
                }
                assertEquals(1, service.outstandingQueries())
                service.submit(fixture.call(id = "second", handles = listOf("b"))).use {
                    assertEquals("OBSERVATION_BUSY", output(it).getValue("reason").jsonPrimitive.content)
                }
                assertEquals(1, fixture.calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun publicationCapacityCannotBeReusedBeforeClose() {
        val fixture = JobObservationFixture().apply { values["a"] = value("a", true) }
        fixture.service(JobObservationLimits(maxObservers = 1, maxPerSession = 1)).use { service ->
            service.submit(fixture.call()).use { first ->
                assertEquals("CONDITION_MET", output(first).getValue("reason").jsonPrimitive.content)
                service.submit(fixture.call(id = "second")).use {
                    assertEquals("OBSERVATION_BUSY", output(it).getValue("reason").jsonPrimitive.content)
                }
            }
            assertEquals(0, service.activeCount())
        }
    }

    @Test fun revocationAfterAQueryDiscardsTheLateResult() {
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a", true)
                queryAction = { binding ->
                    current = false
                    values.getValue(binding.handle)
                }
            }
        fixture.service().use { service ->
            service.submit(fixture.call()).use {
                val result = it.result.get(5, TimeUnit.SECONDS) as ToolExecutorResult.CancelledWithEffectTruth
                assertTrue(result.sideEffectFree)
                assertFalse(result.requiresReview)
            }
        }
    }

    @Test fun queryFailureDoesNotLeakInternalErrorOrDeclareJobFailed() {
        val fixture =
            JobObservationFixture().apply {
                values["a"] = value("a")
                queryAction = { error("private-fixture-secret") }
            }
        fixture.service().use { service ->
            service.submit(fixture.call()).use {
                val result = output(it)
                assertEquals("SOURCE_UNAVAILABLE", result.getValue("reason").jsonPrimitive.content)
                assertFalse(result.toString().contains("private-fixture-secret"))
            }
        }
    }

    private fun output(submission: JobObservationSubmission): JsonObject =
        (submission.result.get(5, TimeUnit.SECONDS) as ToolExecutorResult.Completed).output.jsonObject
}
