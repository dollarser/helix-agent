package com.helix.app.plugin

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.extensions.mobileuse.tools.AutomationTools
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

/** Synthetic tool-boundary checks in the packaged Android runtime; no OS grants or external UI actions. */
class MobileUseObservationDeviceTest {
    @Test fun changingAndPartialPagesReturnFreshUnsettledState() {
        for (partial in listOf(false, true)) {
            val port = SyntheticObservationPort(partial)
            val output = execute(port).output.jsonObject
            assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
            assertEquals("UNVERIFIED", output.getValue("effectStatus").jsonPrimitive.content)
            assertEquals("UNSETTLED", output.getValue("observationStatus").jsonPrimitive.content)
            val observation = output.getValue("observation").jsonObject
            val node =
                observation
                    .getValue("nodes")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("fresh-${port.reads}", node.getValue("token").jsonPrimitive.content)
            assertEquals(partial.toString(), observation.getValue("truncated").jsonPrimitive.content)
            assertEquals(1, port.actions)
        }
    }

    @Test fun cancellationAfterDispatchDoesNotPublishLateStateOrReplay() {
        val port = SyntheticObservationPort(false)
        port.cancelAfterDispatch = true
        val output = execute(port).output.jsonObject
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("SKIPPED_CANCELLED", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.containsKey("observation"))
        assertEquals(1, port.actions)
        assertEquals(0, port.reads)
    }

    private fun execute(port: SyntheticObservationPort): ToolExecutorResult.Completed {
        val call =
            ExecutableToolCall(
                "synthetic-observation",
                AutomationTools.SCROLL,
                "1",
                Json.parseToJsonElement("""{"token":"original","direction":"forward"}""").jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                object : CancelSignal {
                    override fun isCancelled() = port.cancelled
                },
            )
        return AutomationTools(port).executor(call.toolName).execute(call) as ToolExecutorResult.Completed
    }
}

private class SyntheticObservationPort(
    private val partial: Boolean,
) : AutomationToolPort {
    var reads = 0
    var actions = 0
    var cancelled = false
    var cancelAfterDispatch = false

    override fun snapshot(): AutomationSnapshotResult {
        reads++
        return AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "synthetic.fixture",
                1,
                reads.toLong(),
                Instant.now(),
                listOf(
                    AutomationSnapshotNode(
                        "fresh-$reads",
                        null,
                        0,
                        "ScrollView",
                        "Loading $reads",
                        null,
                        "list",
                        AutomationNodeBounds(0, 0, 100, 100),
                        false,
                        false,
                        false,
                        true,
                        true,
                    ),
                ),
                partial,
            ),
        )
    }

    override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult {
        actions++
        cancelled = cancelAfterDispatch
        return AutomationActionResult(AutomationActionStatus.SUCCEEDED)
    }

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult =
        error("Unexpected global action")
}
