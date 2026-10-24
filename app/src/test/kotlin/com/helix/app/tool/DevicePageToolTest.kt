package com.helix.app.tool

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DevicePageToolTest {
    @Test fun settingsSubpagesRetainTheirExistingNativeDestinations() {
        try {
            assertTrue(run("permissions") is ToolExecutorResult.Completed)
            assertEquals(com.helix.app.ui.SETTINGS_PERMISSIONS_ROUTE, DevicePageTool.pending.value?.route)
            assertTrue(run("session") is ToolExecutorResult.Completed)
            assertEquals(com.helix.app.ui.CONVERSATION_SETTINGS_ROUTE, DevicePageTool.pending.value?.route)
        } finally {
            DevicePageTool.pending.value = null
        }
    }

    private fun run(
        route: String,
        cancel: Boolean = false,
    ) = DevicePageTool.executor().execute(
        ExecutableToolCall(
            "call",
            DevicePageTool.NAME,
            "1",
            buildJsonObject {
                put("route", JsonPrimitive(route))
            },
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            object : CancelSignal {
                override fun isCancelled() = cancel
            },
        ),
    )

    @Test fun rejectsArbitraryRoutes() {
        DevicePageTool.pending.value = null
        assertTrue(run("settings/deleteAccount") is ToolExecutorResult.Failed)
        assertNull(DevicePageTool.pending.value)
    }

    @Test fun cancelledNavigationDoesNotQueue() {
        DevicePageTool.pending.value = null
        assertEquals(ToolExecutorResult.Cancelled, run("models", cancel = true))
        assertNull(DevicePageTool.pending.value)
    }

    @Test fun knownRouteOnlyQueuesNavigation() {
        try {
            assertTrue(run("models") is ToolExecutorResult.Completed)
            assertEquals("models", DevicePageTool.pending.value?.route)
            DevicePageTool.descriptor()
        } finally {
            DevicePageTool.pending.value = null
        }
    }
}
