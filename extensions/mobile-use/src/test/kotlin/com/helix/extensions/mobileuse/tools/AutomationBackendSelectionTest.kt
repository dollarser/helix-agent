package com.helix.extensions.mobileuse.tools

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationBackendState
import com.helix.extensions.mobileuse.automation.AutomationClickBackend
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedSelector
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.extensions.mobileuse.automation.preferredAutomationClickBackend
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationBackendSelectionTest {
    @Test fun onlyReadyGrantsQualifyAndRootWinsEveryCombination() {
        for (root in AutomationBackendState.entries) {
            for (shizuku in AutomationBackendState.entries) {
                val expected =
                    when {
                        root == AutomationBackendState.READY -> AutomationClickBackend.ROOT
                        shizuku == AutomationBackendState.READY -> AutomationClickBackend.SHIZUKU
                        else -> AutomationClickBackend.ACCESSIBILITY
                    }
                assertEquals(expected, preferredAutomationClickBackend(root, shizuku))
            }
        }
    }

    @Test fun autoChoosesRootThenShizukuThenAccessibilityBeforeAnyAction() {
        val port = SelectionPort()
        for (choice in AutomationClickBackend.entries) {
            port.selected = choice
            val result = execute(port) as ToolExecutorResult.Completed
            assertEquals(choice.name.lowercase(), (result.output as JsonObject)["backend"]?.jsonPrimitive?.content)
        }
        assertEquals(listOf("root", "shizuku", "accessibility"), port.actions)
    }

    @Test fun unknownRootEffectNeverReplaysOnAnotherBackend() {
        val port = SelectionPort().apply { status = AutomationActionStatus.ACTION_OUTCOME_UNKNOWN }
        val result = execute(port) as ToolExecutorResult.Failed
        assertTrue(result.requiresReview)
        assertFalse(result.sideEffectFree)
        assertEquals(listOf("root"), port.actions)
        assertEquals(0, port.snapshots)
    }

    @Test fun rootDisappearingAfterSelectionFailsWithoutDowngrade() {
        val port = SelectionPort().apply { status = AutomationActionStatus.ROOT_UNAVAILABLE }
        val result = execute(port) as ToolExecutorResult.Failed
        assertTrue(result.sideEffectFree)
        assertEquals(listOf("root"), port.actions)
    }

    @Test fun backendOverrideIsRefusedAndAdditionalFiltersUseFreshObservation() {
        val port = SelectionPort()
        assertTrue(execute(port, mapOf("backend" to "shizuku")) is ToolExecutorResult.Failed)
        assertTrue(execute(port, mapOf("className" to "Button")) is ToolExecutorResult.Completed)
        assertEquals(listOf("accessibility"), port.actions)
    }

    @Test fun accessibilityFallbackStillChecksTheRequestedPackage() {
        val port = SelectionPort().apply { selected = AutomationClickBackend.ACCESSIBILITY }
        val result = execute(port, mapOf("packageName" to "com.other.app")) as ToolExecutorResult.Failed
        assertEquals("TARGET_CHANGED", result.detail)
        assertTrue(port.actions.isEmpty())
    }

    private fun execute(
        port: SelectionPort,
        extra: Map<String, String> = emptyMap(),
    ): ToolExecutorResult {
        val fields =
            mapOf("packageName" to "com.example.target", "viewId" to "android:id/button1", "text" to "Click") + extra
        return AutomationTools(port).executor(AutomationTools.CLICK_MATCH).execute(
            ExecutableToolCall(
                "call",
                AutomationTools.CLICK_MATCH,
                "5",
                JsonObject(fields.mapValues { JsonPrimitive(it.value) }),
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                NoCancellation,
            ),
        )
    }
}

private class SelectionPort : AutomationToolPort {
    var selected = AutomationClickBackend.ROOT
    var status = AutomationActionStatus.SUCCEEDED
    var snapshots = 0
    val actions = mutableListOf<String>()

    override fun preferredClickBackend() = selected

    override fun rootClick(selector: AutomationPrivilegedSelector) = record("root")

    override fun shizukuClick(selector: AutomationPrivilegedSelector) = record("shizuku")

    override fun nodeAction(request: AutomationNodeActionRequest) = record("accessibility")

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult =
        error("Unexpected global action")

    override fun snapshot(): AutomationSnapshotResult {
        snapshots++
        return AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.example.target",
                1,
                1,
                Instant.now(),
                listOf(
                    AutomationSnapshotNode(
                        "fresh-token",
                        null,
                        0,
                        "Button",
                        "Click",
                        null,
                        "android:id/button1",
                        AutomationNodeBounds(0, 0, 100, 100),
                        true,
                        false,
                        false,
                        false,
                        true,
                    ),
                ),
                false,
            ),
        )
    }

    private fun record(backend: String): AutomationActionResult {
        actions += backend
        return AutomationActionResult(status)
    }
}
