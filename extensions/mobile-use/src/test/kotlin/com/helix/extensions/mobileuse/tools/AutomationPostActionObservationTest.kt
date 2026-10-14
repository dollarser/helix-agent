package com.helix.extensions.mobileuse.tools

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPauseReason
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CancellationException

class AutomationPostActionObservationTest {
    private val port = ObservationPort()
    private val tools = AutomationTools(port)

    private fun call(
        name: String = AutomationTools.SCROLL,
        cancel: CancelSignal = NoCancellation,
        deadline: Instant = Instant.now().plusSeconds(30),
    ): ExecutableToolCall =
        ExecutableToolCall(
            "call",
            name,
            "5",
            Json
                .parseToJsonElement(
                    if (name == AutomationTools.SET_TEXT) {
                        """{"token":"original","text":"Hello"}"""
                    } else {
                        """{"token":"original","direction":"forward"}"""
                    },
                ).jsonObject,
            ExecutionTargetType.LOCAL_ANDROID,
            deadline,
            cancel,
            sessionId = "original-session",
            turnId = "original-turn",
            authorizationScopeRef = "original-scope",
        )

    private fun execute(call: ExecutableToolCall) = tools.executor(call.toolName).execute(call)

    private fun completed(call: ExecutableToolCall = call()): JsonObject =
        (execute(call) as ToolExecutorResult.Completed).output.jsonObject

    @Test fun inputAndScrollReturnFreshStateThroughTheOriginalBoundPort() {
        for (name in listOf(AutomationTools.SCROLL, AutomationTools.SET_TEXT)) {
            val call = call(name)
            val output = completed(call)
            assertSame(call, port.originalCall)
            assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
            assertEquals("UNVERIFIED", output.getValue("effectStatus").jsonPrimitive.content)
            assertEquals("ATTACHED", output.getValue("observationStatus").jsonPrimitive.content)
            val fresh =
                output
                    .getValue("observation")
                    .jsonObject
                    .getValue("nodes")
                    .jsonArray
            assertEquals(
                "fresh",
                fresh
                    .single()
                    .jsonObject
                    .getValue("token")
                    .jsonPrimitive.content,
            )
            val descriptor = tools.descriptors().single { it.name.value == name }
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, output))
        }
        assertEquals(2, port.actions)
        assertEquals(2, port.observations)
    }

    @Test fun failedAndUncertainActionsAreNeverReadOrReplayed() {
        for (status in listOf(
            AutomationActionStatus.TOKEN_UNKNOWN,
            AutomationActionStatus.STALE_TOKEN,
            AutomationActionStatus.SENSITIVE_UI,
            AutomationActionStatus.ACTION_OUTCOME_UNKNOWN,
        )) {
            port.action = status
            val result = execute(call()) as ToolExecutorResult.Failed
            assertTrue(result.detail.startsWith(status.name))
            assertEquals(status == AutomationActionStatus.ACTION_OUTCOME_UNKNOWN, result.requiresReview)
        }
        assertEquals(4, port.actions)
        assertEquals(0, port.observations)
    }

    @Test fun cancellationBeforeExecutionDoesNotDispatchOrRead() {
        assertEquals(
            ToolExecutorResult.Cancelled,
            execute(
                call(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = true
                        },
                ),
            ),
        )
        assertEquals(0, port.actions)
        assertEquals(0, port.observations)
    }

    @Test fun cancellationAfterAcknowledgementSkipsReadingWithoutErasingDispatch() {
        var cancelled = false
        port.afterAction = { cancelled = true }
        val output =
            completed(
                call(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = cancelled
                        },
                ),
            )
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("SKIPPED_CANCELLED", output.getValue("observationStatus").jsonPrimitive.content)
        assertEquals(1, port.actions)
        assertEquals(0, port.observations)
    }

    @Test fun cancellationDuringReadingDoesNotPublishLateTokens() {
        var cancelled = false
        port.beforeRead = { cancelled = true }
        val output =
            completed(
                call(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = cancelled
                        },
                ),
            )
        assertEquals("SKIPPED_CANCELLED", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.containsKey("observation"))
        assertEquals(1, port.actions)
    }

    @Test fun anExpiredOptionalReadDoesNotEraseAnAlreadyKnownAcknowledgement() {
        val acknowledgement = AutomationActionResult(AutomationActionStatus.SUCCEEDED).toToolOutcome()
        val helper = AutomationPostActionObservation(port::snapshot, { error("Expired read must not encode") })
        val output =
            (
                helper.attach(
                    acknowledgement,
                    call(deadline = Instant.EPOCH),
                ) as ToolExecutorResult.Completed
            ).output.jsonObject
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("SKIPPED_DEADLINE", output.getValue("observationStatus").jsonPrimitive.content)
        assertEquals(0, port.observations)
    }

    @Test fun refusedReadPreservesItsScopeStatusWithoutGrantingPermission() {
        port.result =
            AutomationSnapshotResult(
                AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED,
                targetPackage = "other.app",
                pauseReason = AutomationPauseReason.TARGET_CHANGED,
            )
        val output = completed()
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("UNAVAILABLE", output.getValue("observationStatus").jsonPrimitive.content)
        val observation = output.getValue("observation").jsonObject
        assertEquals("TARGET_NOT_ALLOWLISTED", observation.getValue("status").jsonPrimitive.content)
        assertEquals("true", observation.getValue("requiresAuthorization").jsonPrimitive.content)
        assertFalse(observation.containsKey("nodes"))
        assertEquals(1, port.actions)
    }

    @Test fun readExceptionsCannotFabricateAStateOrRewriteTheKnownInput() {
        for (error in listOf(IllegalStateException("private diagnostic"), CancellationException("cancel"))) {
            port.beforeRead = { throw error }
            val output = completed()
            assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
            assertEquals(
                if (error is CancellationException) "SKIPPED_CANCELLED" else "UNAVAILABLE",
                output.getValue("observationStatus").jsonPrimitive.content,
            )
            assertFalse(output.toString().contains("private diagnostic"))
            assertFalse(output.containsKey("observation"))
        }
        assertEquals(2, port.actions)
    }

    @Test fun oversizedObservationIsExplicitlyIncompleteAndRetainsFreshTokensOnly() {
        val original = requireNotNull(port.result.snapshot)
        port.result =
            port.result.copy(
                snapshot =
                    original.copy(
                        nodes =
                            (1..100).map {
                                original.nodes.single().copy(token = "fresh-$it", text = "界".repeat(2000))
                            },
                    ),
            )
        val observation = completed().getValue("observation").jsonObject
        val nodes = observation.getValue("nodes").jsonArray
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.size < 32)
        assertTrue(nodes.toString().toByteArray(Charsets.UTF_8).size <= 12 * 1024 + 2)
        assertEquals("true", observation.getValue("truncated").jsonPrimitive.content)
        assertTrue(
            observation
                .getValue("recoveryHint")
                .jsonPrimitive.content
                .contains("ui.find"),
        )
        assertFalse(observation.toString().contains("original"))
    }

    @Test fun findAndWaitCanReturnOnlyFreshScrollableContainers() {
        val node = requireNotNull(port.result.snapshot).nodes.single()
        port.result =
            port.result.copy(
                snapshot =
                    port.result.snapshot!!.copy(
                        nodes = listOf(node, node.copy(token = "label", scrollable = false)),
                    ),
            )
        for (name in listOf(AutomationTools.FIND, AutomationTools.WAIT)) {
            val call = call(name).copy(args = Json.parseToJsonElement("""{"scrollable":true}""").jsonObject)
            val output = completed(call)
            assertEquals(
                "fresh",
                output
                    .getValue("nodes")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("token")
                    .jsonPrimitive.content,
            )
            val descriptor = tools.descriptors().single { it.name.value == name }
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, call.args))
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, output))
        }
        assertEquals(0, port.actions)
    }

    @Test fun refusedOrMalformedObservationsCannotPublishNodesOrClaimSuccess() {
        val original = port.result
        port.result = original.copy(status = AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED)
        val denied = completed()
        assertEquals("UNAVAILABLE", denied.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(denied.getValue("observation").jsonObject.containsKey("nodes"))
        port.result = AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS)
        val malformed = completed()
        assertEquals("UNAVAILABLE", malformed.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(malformed.containsKey("observation"))
    }

    @Test(expected = CancellationException::class)
    fun cancellationDuringTheOriginalActionStillPropagatesWithoutReading() {
        port.afterAction = { throw CancellationException("original action") }
        try {
            execute(call())
        } finally {
            assertEquals(0, port.observations)
        }
    }

    @Test fun unknownTokenRecoveryDoesNotClaimAWindowOrFilteringCause() {
        val failed =
            AutomationActionResult(
                AutomationActionStatus.TOKEN_UNKNOWN,
            ).toToolOutcome() as ToolExecutorResult.Failed
        assertTrue(failed.sideEffectFree)
        assertFalse(failed.requiresReview)
        assertTrue(failed.detail.contains("no action was dispatched"))
        assertTrue(failed.detail.contains("scrollable=true"))
        assertFalse(failed.detail.contains("filtered"))
    }
}

private class ObservationPort : AutomationToolPort {
    var originalCall: ExecutableToolCall? = null
    var actions = 0
    var observations = 0
    var action = AutomationActionStatus.SUCCEEDED
    var afterAction: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    var result =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "fixture",
                1,
                2,
                Instant.EPOCH,
                listOf(
                    AutomationSnapshotNode(
                        "fresh",
                        null,
                        0,
                        "ScrollView",
                        "Form",
                        null,
                        "form",
                        AutomationNodeBounds(0, 0, 100, 100),
                        false,
                        false,
                        false,
                        true,
                        true,
                    ),
                ),
                false,
            ),
        )

    override fun forCall(call: ExecutableToolCall): AutomationToolPort {
        originalCall = call
        return this
    }

    override fun snapshot(): AutomationSnapshotResult {
        observations++
        beforeRead()
        return result
    }

    override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult {
        actions++
        afterAction()
        return AutomationActionResult(action)
    }

    override fun globalAction(action: AutomationGlobalAction) = error("No global actions expected")
}
