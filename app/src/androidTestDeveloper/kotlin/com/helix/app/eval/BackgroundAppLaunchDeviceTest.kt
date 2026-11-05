package com.helix.app.eval

import android.content.Intent
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
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Explicit opt-in: public Coolapk launch/observation only, using the production Dispatcher. */
class BackgroundAppLaunchDeviceTest {
    @Suppress("LongMethod") // A single scoped journey owns restoration even on ANR/refusal.
    @Test
    fun coolapkLaunchObserveReleaseCycles() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("helixCoolapkAnr") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        check(
            c.storage.turns
                .listActive()
                .isEmpty(),
        )
        val center = AutomationPermissionCenter(app)
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val savedScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
        val savedExecution = lines.lines(MobileUseGrantStore.EXECUTION_KEY)

        val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
        val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
        try {
            check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            store.configureGlobal(setOf("com.coolapk.market"), false)
            store.configureExecution(background = true, allowMoveFromMain = true)
            c.pluginService.setEnabled(plugin.id, true)
            app.startActivity(
                requireNotNull(
                    app.packageManager.getLaunchIntentForPackage(app.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val cycles = InstrumentationRegistry.getArguments().getString("helixAnrCycles")?.toInt() ?: 3
            require(cycles > 0)
            repeat(cycles) { cycle ->
                val session = "anr-observe-${UUID.randomUUID()}"
                val turn = "$session-turn"
                val now = System.currentTimeMillis()
                c.storage.sessions.create(session, "Coolapk ANR observation", null, null, now)
                c.storage.turns.start(turn, session, now)
                c.pluginService.catalog.select(session, plugin.id, true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    now,
                )
                var sequence = 0

                fun output(
                    name: String,
                    args: JsonObject = buildJsonObject {},
                ): JsonObject {
                    val descriptor = requireNotNull(c.toolPipeline.resolveLatest(name))
                    val result =
                        c.toolPipeline.dispatcher.dispatch(
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
                    check(result is ToolDispatchOutcome.Succeeded) { "$name: $result" }
                    return Json.parseToJsonElement(result.result.payload).jsonObject
                }
                try {
                    report("ANR_CYCLE=$cycle;PHASE=launch;UPTIME=${SystemClock.uptimeMillis()}")
                    val start = SystemClock.elapsedRealtime()
                    val launched = output("ui.launch", buildJsonObject { put("packageName", "com.coolapk.market") })
                    report("ANR_CYCLE=$cycle;LAUNCH_MS=${SystemClock.elapsedRealtime() - start};RESULT=$launched")
                    assertEquals("DISPATCHED", launched.getValue("status").jsonPrimitive.content)
                    SystemClock.sleep(20_000)
                    val device = output("ui.device")
                    report("ANR_CYCLE=$cycle;DEVICE=$device")
                    assertEquals("READY", device.getValue("status").jsonPrimitive.content)
                    assertTrue(device.getValue("displayId").jsonPrimitive.int > 0)
                    assertEquals("com.coolapk.market", device.getValue("packageName").jsonPrimitive.content)
                    val image = output("ui.screenshot", buildJsonObject { put("frame", device.getValue("frame")) })
                    assertEquals("SAVED", image.getValue("status").jsonPrimitive.content)
                    report("ANR_CYCLE=$cycle;IMAGE=$image")
                } finally {
                    val row = c.storage.turns.resolve(turn)
                    c.storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (c.chatService.displayExecutions.value.any {
                            it.turnId == turn &&
                                it.state == com.helix.app.chat.DisplayExecutionState.ACTIVE
                        } &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        SystemClock.sleep(50)
                    }

                    val stopped = c.storage.turns.resolve(turn)
                    c.storage.turns.updateState(stopped, TurnState.CANCELLED, stopped.stepCount, null, null)
                    assertFalse(
                        c.chatService.displayExecutions.value.any {
                            it.turnId == turn &&
                                it.state == com.helix.app.chat.DisplayExecutionState.ACTIVE
                        },
                    )
                    c.pluginService.catalog.select(session, plugin.id, false)
                }
            }
        } finally {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, savedScope)
            lines.setLines(MobileUseGrantStore.EXECUTION_KEY, savedExecution)
            c.pluginService.setEnabled(plugin.id, plugin.enabled)
        }
    }

    private fun report(value: String) =
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "$value\n")
            },
        )
}
