package com.helix.tools.framework

import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolOperationClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JobObservationDispatchTest {
    @Test fun realExecutorMarksAuditButSameNamedOrdinaryExecutorCannot() {
        JobObservationDispatchFixture().use { fixture ->
            fixture.observations.values["a"] = fixture.observations.value("a", true)
            fixture.register()
            fixture.dispatcher.dispatchCompletion(fixture.call()).get(5, TimeUnit.SECONDS)
            assertTrue(fixture.events.single().jobObservation)
        }
        JobObservationDispatchFixture().use { fixture ->
            fixture.register(
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall) =
                        ToolExecutorResult.Completed(
                            buildJsonObject {},
                            auditDetail = buildJsonObject { },
                        )
                },
            )
            fixture.dispatcher.dispatchCompletion(fixture.call()).get(5, TimeUnit.SECONDS)
            assertFalse(fixture.events.single().jobObservation)
        }
    }

    @Test fun contextPermissionCheckNeverQueriesAndRejectsForkAndRevocation() {
        JobObservationDispatchFixture().use { fixture ->
            fixture.register()
            val request =
                fixture.call().copy(
                    bindingRef =
                        fixture.registry
                            .snapshot()
                            .single()
                            .ref,
                )
            val evidence = JobObservationEvidence.encode(fixture.observations.value("a", true))
            assertTrue(fixture.dispatcher.mayReadJobObservation(request, evidence))
            assertFalse(fixture.dispatcher.mayReadJobObservation(request.copy(sessionId = "fork"), evidence))
            fixture.observations.current = false
            assertFalse(fixture.dispatcher.mayReadJobObservation(request, evidence))
            fixture.registry.replaceOwner(bindingOwner(ToolOrigin.BuiltInOrigin), emptyList())
            assertFalse(fixture.dispatcher.mayReadJobObservation(request, evidence))
            assertEquals(0, fixture.observations.calls.get())
            assertTrue(fixture.events.isEmpty())
        }
    }

    @Test fun observerReleasesSchedulerSlotAndPreservesBatchOrder() {
        JobObservationDispatchFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.observations.queryAction = { binding ->
                entered.countDown()
                release.awaitChecked()
                fixture.observations.value(binding.handle, true)
            }
            fixture.register()
            val next =
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        entered.awaitChecked()
                        release.countDown()
                        return ToolExecutorResult.Completed(buildJsonObject {})
                    }
                }
            fixture.register(next, fixture.descriptor("fixture.next"))
            val result =
                CompletableFuture.supplyAsync {
                    fixture.scheduler.scheduleBatch(
                        listOf(fixture.call(), fixture.call("next", "fixture.next", buildJsonObject {})),
                    )
                }
            try {
                val batch = result.get(5, TimeUnit.SECONDS)
                assertEquals(2, batch.settlements.size)
                assertTrue(batch.outcomes.all { it is ToolDispatchOutcome.Succeeded })
                assertEquals(setOf("observe", "next"), fixture.events.map { it.correlationId }.toSet())
                assertEquals(2, fixture.events.size)
                assertEquals(0, fixture.service.activeCount())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun invalidInputIsAuditedWithoutStartingQuery() {
        JobObservationDispatchFixture().use { fixture ->
            val schema =
                Json
                    .parseToJsonElement(
                        """{"type":"object",
                          "properties":{"handles":{"type":"array","items":{"type":"string"}}},
                          "required":["handles"]}""",
                    ).jsonObject
            fixture.register(descriptor = fixture.descriptor().copy(inputSchema = schema))
            val result =
                fixture.dispatcher
                    .dispatchCompletion(
                        fixture.call(args = buildJsonObject {}),
                    ).get(5, TimeUnit.SECONDS)
            assertFalse(result is ToolDispatchOutcome.Succeeded)
            assertEquals(0, fixture.observations.calls.get())
            assertEquals(1, fixture.events.size)
        }
    }

    @Test fun observerCannotBeRegisteredAsMutationThroughMetadata() {
        JobObservationDispatchFixture().use { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                fixture.register(
                    descriptor = fixture.descriptor().copy(operationClass = ToolOperationClass.LOCAL_MUTATION),
                )
            }
            assertTrue(fixture.registry.all().isEmpty())
        }
    }

    @Test fun invalidObserverOutputDoesNotCreateEffectUncertainty() {
        JobObservationDispatchFixture().use { fixture ->
            fixture.observations.values["a"] = fixture.observations.value("a", true)
            fixture.register(descriptor = fixture.descriptor(output = "{\"type\":\"string\"}"))
            val result =
                fixture.dispatcher.dispatchCompletion(fixture.call()).get(5, TimeUnit.SECONDS)
                    as ToolDispatchOutcome.ExecutionFailed
            assertEquals(DispatchOutcomeCode.INVALID_OUTPUT, result.code)
            assertTrue(result.sideEffectFree)
            assertFalse(result.requiresReview)
            assertEquals(1, fixture.events.size)
        }
    }

    @Test fun revokedBindingCannotStartAnObservation() {
        JobObservationDispatchFixture().use { fixture ->
            fixture.register()
            val request =
                fixture.call().copy(
                    bindingRef =
                        fixture.registry
                            .snapshot()
                            .single()
                            .ref,
                )
            fixture.registry.replaceOwner(bindingOwner(ToolOrigin.BuiltInOrigin), emptyList())
            val result = fixture.dispatcher.dispatchCompletion(request).get(5, TimeUnit.SECONDS)
            assertFalse(result is ToolDispatchOutcome.Succeeded)
            assertEquals(0, fixture.observations.calls.get())
            assertEquals(1, fixture.events.size)
        }
    }

    @Test fun stopWhileQueryIsBlockedSettlesOnceWithoutCancellingOriginal() {
        JobObservationDispatchFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.observations.queryAction = { binding ->
                entered.countDown()
                release.awaitChecked()
                fixture.observations.value(binding.handle, true)
            }
            fixture.register()
            val future = fixture.dispatcher.dispatchCompletion(fixture.call())
            try {
                entered.awaitChecked()
                assertTrue(fixture.service.stopWaiting("s", "observe"))
                val cancelled = future.get(5, TimeUnit.SECONDS) as ToolDispatchOutcome.ExecutionFailed
                assertEquals(DispatchOutcomeCode.CANCELLED_AFTER_START, cancelled.code)
                assertTrue(cancelled.sideEffectFree)
                assertFalse(cancelled.requiresReview)
                assertEquals(1, fixture.events.size)
                assertEquals(
                    "RUNNING",
                    fixture.observations.values
                        .getValue("a")
                        .state,
                )
                release.countDown()
                assertEquals(cancelled, future.get(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }
}
