package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit coordinate fixture, supplied only after inspecting the emulator's current screenshot. */
class CurrentScreenGestureDeviceTest {
    @Test
    @Suppress("LongMethod") // One bounded action with original profile and grant restoration.
    fun tapObservedTargetThroughDispatcher() {
        val args = InstrumentationRegistry.getArguments()
        val snapshotOnly = args.getString("helixSnapshotOnly") == "true"
        assumeTrue(snapshotOnly || args.getString("helixObservedTap") == "true")
        val x = args.getString("x")?.toInt() ?: 0
        val y = args.getString("y")?.toInt() ?: 0
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val center = AutomationPermissionCenter(app)
        val session = "observed-tap-${System.nanoTime()}"
        val turn = "$session-turn"
        val old = c.profileStore.profile
        c.storage.sessions.create(session, "Observed gesture fixture", null, null, System.currentTimeMillis())
        c.storage.turns.start(turn, session, System.currentTimeMillis())
        try {
            c.profileStore.switchTo(SafetyProfile.ADVANCED)
            c.pluginService.catalog.select(
                session,
                c.pluginService
                    .list()
                    .single { it.native?.pluginId == "mobile-use" }
                    .id,
                true,
            )
            c.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                System.currentTimeMillis(),
            )
            check(
                com.helix.app.eval
                    .selectMobileUseForTest(session, emptySet(), true),
            )

            var sequence = 0

            fun dispatch(
                name: String,
                arguments: JsonObject,
            ): ToolDispatchOutcome =
                c.toolPipeline.dispatcher.dispatch(
                    ToolDispatchRequest(
                        toolCallId = "$session-${++sequence}-$name",
                        turnId = turn,
                        sessionId = session,
                        toolName = ToolName(name),
                        toolVersion = requireNotNull(c.toolPipeline.resolveLatest(name)).version,
                        args = arguments,
                        mode = AgentMode.ACT,
                        profile = SafetyProfile.ADVANCED,
                        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                        dataOrigin = DataOrigin.WORKSPACE,
                        scope = requireNotNull(center.conversationGrant(session)).scope,
                        uiToken = "chat:$turn",
                    ),
                )

            val device = readWhenReady("ui.device", "READY", ::dispatch)
            println("OBSERVED_DEVICE=$device")
            val semantic = readWhenReady("ui.snapshot", "SUCCESS", ::dispatch)
            println("OBSERVED_SEMANTIC=${semantic.result.payload}")
            if (snapshotOnly) {
                assertTrue("$semantic", semantic is ToolDispatchOutcome.Succeeded)
                val result =
                    Json
                        .parseToJsonElement(
                            semantic.result.payload,
                        ).jsonObject
                assertTrue(result.getValue("status").jsonPrimitive.content == "SUCCESS")
                assertTrue(result.getValue("backend").jsonPrimitive.content in setOf("root", "shizuku"))
                args.getString("helixClickLabel")?.let { label ->
                    val found = dispatch("ui.find", buildJsonObject { put("text", label) })
                    assertTrue("$found", found is ToolDispatchOutcome.Succeeded)
                    val payload =
                        Json
                            .parseToJsonElement(
                                (found as ToolDispatchOutcome.Succeeded).result.payload,
                            ).jsonObject
                    val token =
                        requireNotNull(payload["suggestedClickToken"]) { "Missing unique click target: $payload" }
                            .jsonPrimitive.content
                    val clicked = dispatch("ui.click", buildJsonObject { put("token", token) })
                    assertTrue("$clicked", clicked is ToolDispatchOutcome.Succeeded)
                    println("SEMANTIC_NODE_ACTION=$clicked")
                }
                return
            }
            println("OBSERVED_DEVICE=$device")
            val frame =
                Json
                    .parseToJsonElement(device.result.payload)
                    .jsonObject
                    .getValue("frame")
                    .jsonPrimitive.content
            val capture = dispatch("ui.screenshot", buildJsonObject { put("frame", frame) })
            assertTrue("$capture", capture is ToolDispatchOutcome.Succeeded)
            val gesture =
                dispatch(
                    "ui.gesture",
                    Json
                        .parseToJsonElement(
                            """{"frame":"$frame","strokes":[{"points":[{"x":$x,"y":$y}],"durationMillis":100}]}""",
                        ).jsonObject,
                )
            println("OBSERVED_GESTURE=$gesture")
            assertTrue("$gesture", gesture is ToolDispatchOutcome.Succeeded)
        } finally {
            com.helix.app.eval
                .deselectMobileUseForTest(session)
            c.storage.deleteSessionPermanently(session)
            c.profileStore.switchTo(old)
        }
    }

    private fun readWhenReady(
        name: String,
        expected: String,
        dispatch: (String, JsonObject) -> ToolDispatchOutcome,
    ): ToolDispatchOutcome.Succeeded {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        var outcome: ToolDispatchOutcome
        do {
            outcome = dispatch(name, JsonObject(emptyMap()))
            if (outcome is ToolDispatchOutcome.Succeeded) {
                val status =
                    Json
                        .parseToJsonElement(outcome.result.payload)
                        .jsonObject["status"]
                        ?.jsonPrimitive
                        ?.content
                if (status == expected) return outcome
            }
            android.os.SystemClock.sleep(100)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        error("$name did not become $expected: $outcome")
    }
}
