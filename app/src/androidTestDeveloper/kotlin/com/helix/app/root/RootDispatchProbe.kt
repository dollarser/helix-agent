package com.helix.app.root

import com.helix.app.AppContainer
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.policy.SessionPermissionConfig
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HXA-095: real App/Room/Dispatcher/RootModule, no fake privileged port or model service. */
internal class RootDispatchProbe(
    private val container: AppContainer,
) {
    private val session = "root-device-${System.nanoTime()}"
    private val turn = "$session-turn"

    init {
        val now = System.currentTimeMillis()
        container.storage.sessions.create(session, "Root device fixture", null, null, now)
        container.storage.turns.start(turn, session, now)
        container.sessionPermissionEdit.saveSessionConfig(
            session,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            now,
        )
    }

    fun verifyFiveToolsAndDisable() {
        assertEquals("active", success("root.status", "{}").getValue("session").jsonPrimitive.content)
        val file = success("root.file.read", """{"scopeId":"system-etc","relativePath":"hosts","maxBytes":64}""")
        assertTrue(
            file
                .getValue("windowLength")
                .jsonPrimitive.content
                .toInt() in 1..64,
        )
        assertEquals("hosts", file.getValue("relativePath").jsonPrimitive.content)
        assertEquals(
            "com.helix.agent.developer",
            success(
                "root.package.info",
                """{"packageName":"com.helix.agent.developer"}""",
            ).getValue("packageName").jsonPrimitive.content,
        )
        assertTrue(success("root.process.list", """{"limit":5}""").getValue("processes").jsonArray.size in 1..5)
        assertTrue(
            success("root.log.read", """{"maxLines":5,"minPriority":"I"}""").getValue("lines").jsonArray.size <= 5,
        )
        assertFalse(
            dispatch(
                "root.file.read",
                """{"scopeId":"system-etc","relativePath":"../build.prop"}""",
            ) is ToolDispatchOutcome.Succeeded,
        )
        container.sessionPermissionEdit.setToolAvailability(
            ToolOrigin.BuiltInOrigin.canonicalOf(),
            "root.process.list",
            ToolAvailabilityScope.SESSION,
            session,
            true,
            System.currentTimeMillis(),
        )
        val denied = dispatch("root.process.list", "{}")
        assertTrue(denied is ToolDispatchOutcome.Denied)
        assertEquals(DispatchOutcomeCode.TOOL_DISABLED, (denied as ToolDispatchOutcome.Denied).code)
    }

    fun verifyDisconnectedScopeCannotRead() {
        assertFalse(
            dispatch(
                "root.file.read",
                """{"scopeId":"system-etc","relativePath":"hosts"}""",
            ) is ToolDispatchOutcome.Succeeded,
        )
    }

    private fun success(
        name: String,
        args: String,
    ): JsonObject {
        val result = dispatch(name, args)
        assertTrue("$name must execute through production dispatcher: $result", result is ToolDispatchOutcome.Succeeded)
        return Json.parseToJsonElement((result as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
    }

    private fun dispatch(
        name: String,
        args: String,
    ): ToolDispatchOutcome {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor
                .submit<ToolDispatchOutcome> {
                    container.chatService.dispatchToolCall("$turn-${System.nanoTime()}", turn, name, args)
                }.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
