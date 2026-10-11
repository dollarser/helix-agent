package com.helix.extensions.mobileuse

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MobileUsePresentedExecutorTest {
    private val call =
        ExecutableToolCall(
            "call",
            "ui.snapshot",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(10),
            NoCancellation,
            "chat",
            "turn",
        )
    private val surface = Surface()
    private val completed = ToolExecutorResult.Completed(JsonObject(emptyMap()))
    private var calls = 0
    private var authorized = true
    private val delegate =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                assertTrue(surface.hidden)
                calls++
                surface.allowed = false
                assertTrue(call.cancel.isCancelled())
                return completed
            }
        }
    private val executor =
        MobileUsePresentedExecutor(
            delegate,
            surface,
        ) { if (authorized) null else "MOBILE_USE_AUTHORIZATION_UNAVAILABLE" }

    @Test fun everyBackendHidesBeforeExecutionAndForwardsTakeoverWithoutChangingIdentity() {
        assertEquals(completed, executor.execute(call))
        assertEquals(call, surface.bound)
        assertFalse(surface.hidden)
        assertEquals(1, calls)
    }

    @Test fun missingGrantNeverBindsOrExecutes() {
        authorized = false
        val failure = executor.execute(call) as ToolExecutorResult.Failed
        assertTrue(failure.sideEffectFree)
        assertEquals("MOBILE_USE_AUTHORIZATION_UNAVAILABLE", failure.detail)
        assertNull(surface.bound)
        assertEquals(0, calls)
    }

    @Test fun takeoverAfterHideRefusesAndReleasesWindow() {
        surface.afterHide = { surface.allowed = false }
        val failure = executor.execute(call) as ToolExecutorResult.Failed
        assertTrue(failure.detail.startsWith("MOBILE_USE_TASK_STOPPED"))
        assertTrue(failure.sideEffectFree)
        assertFalse(surface.hidden)
        assertEquals(0, calls)
    }

    @Test fun backendFailureNeverBindsAndIsNotReportedAsTaskStopped() {
        val unavailable = MobileUsePresentedExecutor(delegate, surface) { "MOBILE_USE_BACKEND_UNAVAILABLE" }
        assertEquals("MOBILE_USE_BACKEND_UNAVAILABLE", (unavailable.execute(call) as ToolExecutorResult.Failed).detail)
        assertNull(surface.bound)
        assertEquals(0, calls)
    }

    @Test fun hideFailureOrRevocationNeverExecutes() {
        surface.canHide = false
        assertTrue((executor.execute(call) as ToolExecutorResult.Failed).sideEffectFree)
        surface.canHide = true
        surface.afterHide = { authorized = false }
        assertTrue((executor.execute(call) as ToolExecutorResult.Failed).sideEffectFree)
        assertFalse(surface.hidden)
        assertEquals(0, calls)
    }

    @Test fun delegateFailureReleasesSuppressionWithoutInventingSuccess() {
        val failing =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = error("failure")
            }
        assertThrows(IllegalStateException::class.java) {
            MobileUsePresentedExecutor(failing, surface) { null }.execute(call)
        }
        assertFalse(surface.hidden)
    }

    private class Surface : AutomationRuntimePresentation {
        var bound: ExecutableToolCall? = null
        var hidden = false
        var allowed = true
        var canHide = true
        var afterHide: () -> Unit = {}

        override fun bind(call: ExecutableToolCall): Boolean {
            bound = call
            return allowed
        }

        override fun stopBoundTask() {
            allowed = false
        }

        override fun executionAllowed() = allowed

        override fun ownsWindow(windowId: Int) = false

        override fun hideForOperation(call: ExecutableToolCall): AutoCloseable? {
            if (!canHide) return null
            hidden = true
            afterHide()
            return AutoCloseable { hidden = false }
        }

        override fun hide() = Unit

        override fun close() = Unit
    }
}
