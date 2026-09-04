package com.helix.tools.root

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SystemClock
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootToolsRootlessDeviceTest {
    private val clock = SystemClock()
    private val access = LibsuRootAccess(ApplicationProvider.getApplicationContext())
    private val sessions = RootSessionManager(clock, access::status, access::disconnect)
    private val tools = RootTools(access, sessions)

    @After fun tearDown() = access.disconnect()

    @Test
    fun statusIsAvailableWithoutConstructingARootSession() {
        val result = tools.executor(RootTools.STATUS).execute(call(RootTools.STATUS)) as ToolExecutorResult.Completed
        val output = result.output.jsonObject
        assertEquals("inactive", output.getValue("session").jsonPrimitive.content)
        assertEquals(ExecutionTargetType.LOCAL_ROOT, tools.descriptors().first().executionTarget)
        assertTrue(tools.descriptors().none { it.name.value == "root.exec" })
    }

    @Test
    fun highLevelOperationCannotStartWithoutConnectedRootService() {
        assertThrows(IllegalArgumentException::class.java) { sessions.start(mapOf("system" to "/system/etc")) }
        val result = tools.executor(RootTools.PROCESS_LIST).execute(call(RootTools.PROCESS_LIST))
        assertTrue(result is ToolExecutorResult.Failed)
    }

    private fun call(name: String) =
        ExecutableToolCall(
            "call",
            name,
            "1",
            buildJsonObject {},
            ExecutionTargetType.LOCAL_ROOT,
            clock.now().plusSeconds(30),
            NoCancellation,
        )
}
