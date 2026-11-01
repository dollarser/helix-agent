package com.helix.app.eval

import android.hardware.display.DisplayManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.model.TurnState
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.tools.deviceaccess.DeviceAccess
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Reuses existing shell authorization. Never requests grants or operates a third-party app. */
class BackgroundDisplayDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center = AutomationPermissionCenter(app)
    private val session = "background-fixture-${UUID.randomUUID()}"
    private val turn = "$session-turn"
    private var sequence = 0

    private fun dispatch(
        name: String,
        args: JsonObject = buildJsonObject {},
    ): ToolDispatchOutcome {
        val descriptor = requireNotNull(container.toolPipeline.resolveLatest(name))
        return container.toolPipeline.dispatcher.dispatch(
            ToolDispatchRequest(
                toolCallId = "$session-${++sequence}",
                turnId = turn,
                sessionId = session,
                toolName = ToolName(name),
                toolVersion = descriptor.version,
                args = args,
                mode = AgentMode.ACT,
                profile = SafetyProfile.ADVANCED,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                dataOrigin = DataOrigin.WORKSPACE,
                scope = requireNotNull(center.conversationGrant(session)).scope,
                uiToken = "chat:$turn",
            ),
        )
    }

    private fun output(
        name: String,
        args: JsonObject = buildJsonObject {},
    ): JsonObject {
        val result = dispatch(name, args)
        assertTrue("$name: $result", result is ToolDispatchOutcome.Succeeded)
        return Json.parseToJsonElement((result as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
    }

    @Suppress("LongMethod") // One production-boundary journey retains all restoration in finally.
    @Test
    fun productionToolsRouteToOwnedDisplayAndReleaseItAfterDurableCancellation() {
        check(DeviceAccess.shizukuReady() && DeviceAccess.shizukuGranted()) {
            "Existing Shizuku authorization required; this fixture never requests it"
        }
        check(
            container.storage.turns
                .listActive()
                .isEmpty(),
        ) {
            "Daily package has an active task; keep its configuration intact and run this fixture when idle"
        }
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val savedScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
        val savedDisplay = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
        val profile = container.profileStore.profile
        val plugin = container.pluginService.list().single { it.native?.pluginId == "mobile-use" }
        val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
        val fixturePackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val now = System.currentTimeMillis()
        container.storage.sessions.create(session, "Background synthetic fixture", null, null, now)
        container.storage.turns.start(turn, session, now)
        var displayId: Int? = null
        try {
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            container.pluginService.setEnabled(plugin.id, true)
            store.configureGlobal(setOf(fixturePackage), false)
            store.configureExecution(background = true, allowMoveFromMain = false)
            container.pluginService.catalog.select(session, plugin.id, true)
            container.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                now,
            )
            val launch = buildJsonObject { put("packageName", fixturePackage) }
            assertTrue(dispatch("ui.launch", launch) is ToolDispatchOutcome.Denied)
            container.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                now + 1,
            )
            assertEquals("DISPATCHED", output("ui.launch", launch).getValue("status").jsonPrimitive.content)
            var device = output("ui.device")
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (device["frame"] == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
                device = output("ui.device")
            }
            assertEquals("READY", device.getValue("status").jsonPrimitive.content)
            displayId =
                device
                    .getValue("displayId")
                    .jsonPrimitive.content
                    .toInt()
            assertTrue(displayId > 0)
            assertEquals(fixturePackage, device.getValue("packageName").jsonPrimitive.content)
            println("BACKGROUND_DISPLAY=$displayId;focus=${device["independentFocus"]}")
            val activePresentation =
                container.chatService.displayExecutions.value.single {
                    it.sessionId == session &&
                        it.turnId == turn
                }
            assertEquals(com.helix.app.chat.DisplayExecutionState.ACTIVE, activePresentation.state)
            assertEquals("com.helix.agent.developer.test", activePresentation.lastObservedPackage)
            val observed = readyFixtureSnapshot()
            val nodes = observed.getValue("nodes").jsonArray.map { it.jsonObject }
            val input = nodes.single { it["editable"]?.jsonPrimitive?.content == "true" }
            val original = input.getValue("token").jsonPrimitive.content
            typeAfterReadOnlyRefreshIfStale(observed)
            assertTrue(
                dispatch("ui.click", buildJsonObject { put("token", original) }) !is ToolDispatchOutcome.Succeeded,
            )
            output(
                "ui.click_match",
                buildJsonObject {
                    put("packageName", fixturePackage)
                    put("viewId", "android:id/button1")
                    put("text", "Increment")
                },
            )
            val after = output("ui.snapshot").toString()
            assertTrue(after, after.contains("BACKGROUND_COUNT:1"))
            assertTrue(after, after.contains("Hello background"))
            device = output("ui.device")
            val frame = device.getValue("frame").jsonPrimitive.content
            val capture = output("ui.screenshot", buildJsonObject { put("frame", frame) })
            assertEquals("SAVED", capture.getValue("status").jsonPrimitive.content)
            if (InstrumentationRegistry.getArguments().getString("helixDisconnect") == "true") {
                verifyRealHelperDisconnect(displayId)
            }
            val row = container.storage.turns.resolve(turn)
            container.storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
            val releaseDeadline = SystemClock.elapsedRealtime() + 5_000
            val manager = app.getSystemService(DisplayManager::class.java)
            while (manager.getDisplay(displayId) != null && SystemClock.elapsedRealtime() < releaseDeadline) {
                SystemClock.sleep(100)
            }
            assertEquals(null, manager.getDisplay(displayId))
            assertFalse(
                container.storage.auditEvents
                    .listByCorrelation("$session-1")
                    .isEmpty(),
            )

            fun hostPresentation() =
                container.chatService.displayExecutions.value.singleOrNull {
                    it.sessionId == session && it.turnId == turn
                }
            val presentationDeadline = SystemClock.elapsedRealtime() + 5000
            while (hostPresentation()?.state !in
                setOf(
                    com.helix.app.chat.DisplayExecutionState.RELEASED,
                    com.helix.app.chat.DisplayExecutionState.CONNECTION_LOST,
                ) &&
                SystemClock.elapsedRealtime() < presentationDeadline
            ) {
                SystemClock.sleep(50)
            }
            val disconnected = InstrumentationRegistry.getArguments().getString("helixDisconnect") == "true"
            val expected =
                if (disconnected) {
                    com.helix.app.chat.DisplayExecutionState.CONNECTION_LOST
                } else {
                    com.helix.app.chat.DisplayExecutionState.RELEASED
                }
            assertEquals(expected, requireNotNull(hostPresentation()).state)
            println("BACKGROUND_RELEASED=$displayId;dispatches=$sequence;HOST_PRESENTATION=$expected")
        } finally {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, savedScope)
            lines.setLines(MobileUseGrantStore.EXECUTION_KEY, savedDisplay)
            container.pluginService.catalog.select(session, plugin.id, false)
            container.storage.deleteSessionPermanently(session)
            container.pluginService.setEnabled(plugin.id, plugin.enabled)
            container.profileStore.switchTo(profile)
        }
    }

    private fun verifyRealHelperDisconnect(displayId: Int) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "BACKGROUND_DISCONNECT_READY display=$displayId\n")
            },
        )
        val deadline = SystemClock.elapsedRealtime() + 20_000

        fun disconnected() =
            container.chatService.displayExecutions.value.any {
                it.sessionId == session && it.turnId == turn &&
                    it.state == com.helix.app.chat.DisplayExecutionState.CONNECTION_LOST
            }
        while ((!disconnected() || app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
        }
        assertTrue("Expected actual original-helper Binder death; no automatic action replay", disconnected())
        assertEquals(null, app.getSystemService(DisplayManager::class.java).getDisplay(displayId))
    }

    private fun readyFixtureSnapshot(): JsonObject {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var observed = output("ui.snapshot")

        fun ready(): Boolean =
            observed["nodes"]?.jsonArray?.any {
                it.jsonObject["editable"]?.jsonPrimitive?.content == "true"
            } == true
        while (!ready() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            observed = output("ui.snapshot")
        }
        assertTrue("Fixture semantic tree unavailable: ${observed["status"]}", ready())
        return observed
    }

    private fun typeAfterReadOnlyRefreshIfStale(initial: JsonObject) {
        var observed = initial
        repeat(3) {
            val input =
                observed
                    .getValue("nodes")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it["editable"]?.jsonPrimitive?.content == "true" }
            val result =
                dispatch(
                    "ui.set_text",
                    buildJsonObject {
                        put("token", input.getValue("token"))
                        put("text", "Hello background")
                    },
                )
            if (result is ToolDispatchOutcome.Succeeded) return
            check(
                result is ToolDispatchOutcome.ExecutionFailed && result.sideEffectFree &&
                    result.detail.startsWith("STALE_TOKEN"),
            ) { "Unexpected input outcome; do not replay: $result" }
            println("BACKGROUND_READ_ONLY_REFRESH_AFTER_STALE")
            SystemClock.sleep(300)
            observed = readyFixtureSnapshot()
        }
        error("Background target remained stale after bounded read-only refreshes")
    }
}
