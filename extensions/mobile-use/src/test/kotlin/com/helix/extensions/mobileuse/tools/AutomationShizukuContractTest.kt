package com.helix.extensions.mobileuse.tools

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationClickBackend
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedSelector
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.extensions.mobileuse.automation.privilegedPointInside
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationShizukuContractTest {
    @Test fun privilegedPointMustRemainInsideTheObservedWindowAndRotation() {
        val target =
            AutomationDisplayTarget(
                "com.example.installer",
                1,
                0,
                1000,
                2000,
                0,
                AutomationNodeBounds(100, 200, 900, 1800),
            )
        assertTrue(privilegedPointInside(target, 500, 1000, 0))
        for ((x, y, rotation) in listOf(
            Triple(99, 1000, 0),
            Triple(900, 1000, 0),
            Triple(500, 1800, 0),
            Triple(500, 199, 0),
            Triple(500, 1000, 1),
        )) {
            assertFalse(privilegedPointInside(target, x, y, rotation))
        }
    }

    private val port = ShizukuPortFixture()
    private val tools = AutomationTools(port)
    private val fields =
        mapOf(
            "packageName" to "com.example.installer",
            "viewId" to "android:id/button1",
            "text" to "Update",
        )

    @Test fun exactClickUsesBoundOriginalCallAndKeepsExternalActionPolicy() {
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.CLICK_MATCH }
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, descriptor.operationClass)
        val input = json(fields)
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, input))
        assertTrue(execute(input) is ToolExecutorResult.Completed)
        assertEquals("original-conversation", port.call?.sessionId)
        assertEquals("original-scope", port.call?.authorizationScopeRef)
        assertEquals(1, port.clicks)
        assertEquals(0, port.snapshots)
    }

    @Test fun ambiguousOrIgnoredFiltersAreRejectedWithoutDispatch() {
        for (extra in listOf("match", "timeoutMillis", "className", "checked", "contentDescription")) {
            assertTrue(execute(json(fields + (extra to "anything"))) is ToolExecutorResult.Failed)
        }
        assertEquals(0, port.clicks)
    }

    @Test fun missingOrMalformedSelectorIsRejected() {
        for (key in listOf("packageName", "viewId", "text")) {
            assertTrue(execute(json(fields - key)) is ToolExecutorResult.Failed)
        }
        assertTrue(execute(json(fields + ("packageName" to "com.bad;id"))) is ToolExecutorResult.Failed)
        assertTrue(execute(json(fields + ("text" to ""))) is ToolExecutorResult.Failed)
        assertEquals(0, port.clicks)
    }

    @Test fun unknownBackendDoesNotFallBackToAccessibility() {
        assertTrue(execute(json(fields + ("backend" to "invalid"))) is ToolExecutorResult.Failed)
        assertEquals(0, port.clicks)
        assertEquals(0, port.snapshots)
    }

    @Test fun unavailableBackendAndUnknownOutcomeRemainDifferent() {
        port.status = AutomationActionStatus.SHIZUKU_UNAVAILABLE
        val missing = execute(json(fields)) as ToolExecutorResult.Failed
        assertTrue(missing.sideEffectFree)
        port.status = AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
        val unknown = execute(json(fields)) as ToolExecutorResult.Failed
        assertFalse(unknown.sideEffectFree)
        assertTrue(unknown.requiresReview)
        assertEquals(2, port.clicks)
    }

    private fun execute(args: JsonObject) =
        tools.executor(AutomationTools.CLICK_MATCH).execute(
            ExecutableToolCall(
                "call",
                AutomationTools.CLICK_MATCH,
                "4",
                args,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(60),
                NoCancellation,
                sessionId = "original-conversation",
                authorizationScopeRef = "original-scope",
            ),
        )

    private fun json(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
}

private class ShizukuPortFixture : AutomationToolPort {
    override fun preferredClickBackend() = AutomationClickBackend.SHIZUKU

    var call: ExecutableToolCall? = null
    var clicks = 0
    var snapshots = 0
    var status = AutomationActionStatus.SUCCEEDED

    override fun forCall(call: ExecutableToolCall): AutomationToolPort = this.also { this.call = call }

    override fun shizukuClick(selector: AutomationPrivilegedSelector): AutomationActionResult {
        clicks++
        return AutomationActionResult(status)
    }

    override fun snapshot(): AutomationSnapshotResult {
        snapshots++
        return AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
    }

    override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult =
        error("Unexpected Accessibility action")

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult =
        error("Unexpected global action")
}
