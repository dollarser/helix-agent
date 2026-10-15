package com.helix.extensions.mobileuse.tools

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationTerminationTest {
    private val port = ObservationPort()
    private val tools = AutomationTools(port)

    @Test fun cancellationBeforeEntryDoesNotBindOrOperateAnySemanticTool() {
        for (descriptor in tools.descriptors()) {
            val result =
                execute(descriptor.name.value, cancelled = { true }) as ToolExecutorResult.CancelledWithEffectTruth
            assertTrue(result.sideEffectFree)
            assertFalse(result.requiresReview)
            assertEquals("AUTOMATION_CANCELLED_BEFORE_DISPATCH", result.detail)
        }
        assertEquals(0, port.bindings)
        assertEquals(0, port.snapshots)
    }

    @Test fun expiredCallNeverBindsOrOperatesAnySemanticTool() {
        for (descriptor in tools.descriptors()) {
            val result = execute(descriptor.name.value, Instant.EPOCH) as ToolExecutorResult.TimedOutWithEffectTruth
            assertTrue(result.sideEffectFree)
            assertFalse(result.requiresReview)
            assertEquals("AUTOMATION_DEADLINE_BEFORE_DISPATCH", result.detail)
        }
        assertEquals(0, port.bindings)
        assertEquals(0, port.snapshots)
    }

    @Test fun cancellationDuringClickMatchObservationDoesNotClaimAnUnknownClick() {
        val result =
            execute(AutomationTools.CLICK_MATCH, cancelled = { port.snapshots > 0 }) as
                ToolExecutorResult.CancelledWithEffectTruth
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertEquals("AUTOMATION_WAIT_CANCELLED", result.detail)
        assertEquals(1, port.snapshots)
    }

    @Test fun deadlineBeforeClickMatchObservationIsConfirmedWithoutAnAction() {
        val result =
            AutomationClickMatchExecutor(port).execute(
                call(AutomationTools.CLICK_MATCH, Instant.EPOCH),
                com.helix.extensions.mobileuse.automation
                    .AutomationFindQuery(text = "Continue"),
                timeoutMillis = 1_000,
                pollMillis = 100,
            ) as ToolExecutorResult.TimedOutWithEffectTruth
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertEquals(0, port.snapshots)
    }

    private fun execute(
        name: String,
        deadline: Instant = Instant.now().plusSeconds(10),
        cancelled: (() -> Boolean)? = null,
    ): ToolExecutorResult =
        tools.executor(name).execute(
            call(name, deadline).copy(
                cancel =
                    cancelled?.let { signal ->
                        object : CancelSignal {
                            override fun isCancelled() = signal()
                        }
                    } ?: NoCancellation,
            ),
        )

    private fun call(
        name: String,
        deadline: Instant,
    ) = ExecutableToolCall(
        "original-call",
        name,
        "1",
        buildJsonObject {
            put("text", "Continue")
            put("timeoutMillis", 1_000)
        },
        ExecutionTargetType.LOCAL_ANDROID,
        deadline,
        NoCancellation,
    )

    private class ObservationPort : AutomationToolPort {
        var bindings = 0
        var snapshots = 0

        override fun forCall(call: ExecutableToolCall): AutomationToolPort {
            bindings++
            return this
        }

        override fun snapshot(): AutomationSnapshotResult {
            snapshots++
            return AutomationSnapshotResult(AutomationSnapshotStatus.UNSUPPORTED_UI)
        }

        override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult =
            error("No node action may run after cancellation or timeout")

        override fun globalAction(action: AutomationGlobalAction): AutomationActionResult =
            error("No global action may run after cancellation or timeout")
    }
}
