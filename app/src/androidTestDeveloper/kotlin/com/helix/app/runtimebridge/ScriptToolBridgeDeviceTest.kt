package com.helix.app.runtimebridge

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Synthetic read-only child calls through production gateway; never contacts an external account. */
class ScriptToolBridgeDeviceTest {
    @Test fun isolatedJavascriptDelegatesAndPersistsChildrenWithoutNativeAccess() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val storage = container.storage
        clearBridgeTestSessions(app)
        check(storage.turns.listActive().isEmpty())
        val settings = HostToolBridgeSettings(app)
        val oldEnabled = settings.enabled

        val id = "script-fixture-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        storage.sessions.create(id, "Script bridge synthetic fixture", null, null, now)
        storage.turns.start(id, id, now)
        advanceBridgeTestTurn(app, id)
        storage.sessionPermissionConfigs.setForSession(
            id,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            now,
        )
        try {
            check(container.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            settings.setEnabled(true)
            val result =
                container.chatService.dispatchToolCall(
                    "$id-parent",
                    id,
                    "code.javascript.run",
                    buildJsonObject {
                        put("toolBridge", true)
                        put("code", "const r=helix.call('time.now',{}); return {child:r,native:typeof native};")
                    }.toString(),
                )
            val output = succeeded(result)
            val body = Json.parseToJsonElement(output.getValue("result").jsonPrimitive.content).jsonObject
            assertEquals("undefined", body.getValue("native").jsonPrimitive.content)
            assertEquals(
                "SUCCEEDED",
                body
                    .getValue("child")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val calls = storage.toolCalls.listByTurn(id)
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.state == "COMPLETED" })
            assertTrue(calls.any { it.name == "time.now" && it.callId.startsWith("script-") })
            settings.setEnabled(false)
            verifyDisabled(app, id)
        } finally {
            settings.setEnabled(oldEnabled)
            storage.deleteSessionPermanently(id)
        }
    }

    private fun verifyDisabled(
        app: HelixApplication,
        id: String,
    ) {
        val denied =
            app.appContainer.chatService.dispatchToolCall(
                "$id-off",
                id,
                "code.javascript.run",
                buildJsonObject {
                    put("toolBridge", true)
                    put("code", "throw new Error('must not start');")
                }.toString(),
            )
        assertTrue(denied !is ToolDispatchOutcome.Succeeded)
        assertEquals(
            1,
            app.appContainer.storage.toolCalls
                .listByTurn(id)
                .count { it.name == "time.now" },
        )
    }

    private fun succeeded(outcome: ToolDispatchOutcome): JsonObject {
        assertTrue(outcome.toString(), outcome is ToolDispatchOutcome.Succeeded)
        return Json.parseToJsonElement((outcome as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
    }
}
