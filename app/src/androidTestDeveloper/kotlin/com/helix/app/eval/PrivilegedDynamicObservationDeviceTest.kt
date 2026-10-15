package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
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
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicitly prepared Shizuku only; an 80 ms counter changes content without changing the window. */
class PrivilegedDynamicObservationDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val session = "dynamic-observation-${System.nanoTime()}"
    private val turn = "$session-turn"
    private var count = 0

    @Test
    @Suppress("LongMethod") // One owned session verifies observation and stale-action boundaries before cleanup.
    fun changingContentCanBeReadWithoutRenewingOldGestureCredentials() {
        val optIn = InstrumentationRegistry.getArguments().getString("helixDynamicObservation")
        org.junit.Assume.assumeTrue("Requires explicitly prepared Shizuku", optIn != null)
        require(optIn == "true")
        require(android.provider.Settings.canDrawOverlays(app)) { "Requires an explicitly prepared overlay grant" }
        val profile = c.profileStore.profile
        val selection = MobileUseEvaluationSelection(app)
        c.storage.sessions.create(session, "Dynamic observation fixture", null, null, System.currentTimeMillis())
        c.storage.turns.start(turn, session, System.currentTimeMillis())
        c.chatService.refreshBackgroundTasksNow()
        try {
            c.profileStore.switchTo(SafetyProfile.ADVANCED)
            selection.select(session, emptySet(), true)
            c.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                System.currentTimeMillis(),
            )
            app.startActivity(
                Intent()
                    .setComponent(
                        ComponentName("${app.packageName}.test", AutomationEvaluationActivity::class.java.name),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("dynamicContent", true),
            )
            SystemClock.sleep(1200)
            val projectionDeadline = SystemClock.elapsedRealtime() + 5000
            while (c.chatService.backgroundTasks.value
                    .none { it.id == turn } &&
                SystemClock.elapsedRealtime() < projectionDeadline
            ) {
                SystemClock.sleep(50)
            }
            assertTrue(
                c.chatService.backgroundTasks.value
                    .any { it.id == turn },
            )
            val ticks = mutableSetOf<String>()
            repeat(3) {
                val snapshot = execute("ui.snapshot")
                assertEquals("SUCCESS", snapshot["status"]?.jsonPrimitive?.content)
                assertEquals("shizuku", snapshot["backend"]?.jsonPrimitive?.content)
                ticks +=
                    snapshot.getValue("nodes").jsonArray.mapNotNull {
                        it.jsonObject["text"]
                            ?.jsonPrimitive
                            ?.content
                            ?.takeIf { text -> text.startsWith("TICK:") }
                    }
                SystemClock.sleep(120)
            }
            assertEquals(3, ticks.size)
            val frame = execute("ui.device").getValue("frame").jsonPrimitive.content
            println("OVERLAY_PROBE_READY")
            SystemClock.sleep(2500)
            val image = execute("ui.screenshot", """{"frame":"$frame"}""")
            assertEquals("SAVED", image["status"]?.jsonPrimitive?.content)
            assertTrue(
                image
                    .getValue("sizeBytes")
                    .jsonPrimitive.content
                    .toLong() > 0,
            )
            assertControlsAbsent(image)
            val action =
                dispatch(
                    "ui.gesture",
                    """{"frame":"$frame","strokes":[
                        {"points":[{"x":540,"y":600}],"startMillis":0,"durationMillis":100}]}""",
                )
            assertTrue(action is ToolDispatchOutcome.ExecutionFailed)
            val refused = action as ToolDispatchOutcome.ExecutionFailed
            assertEquals("ACTION_NOT_DISPATCHED", refused.detail)
            assertTrue(refused.sideEffectFree)
            println("DYNAMIC_OBSERVATION_PASSED:three fresh snapshots, pixels, stale gesture refused")
        } finally {
            selection.close()
            c.storage.deleteSessionPermanently(session)
            c.chatService.refreshBackgroundTasksNow()
            c.profileStore.switchTo(profile)
        }
    }

    private fun assertControlsAbsent(image: JsonObject) {
        val reference = image.getValue("reference").jsonPrimitive.content
        require(reference.startsWith("scope:app:output/"))
        val file = app.filesDir.resolve("workspaces/app/${reference.removePrefix("scope:app:")}")
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
        try {
            val density = app.resources.displayMetrics.density
            val x = bitmap.width - (40 * density).toInt()
            for (yDp in listOf(120, 180)) {
                assertEquals(
                    "Control footprint must contain fixture pixels",
                    android.graphics.Color.rgb(25, 118, 108),
                    bitmap.getPixel(x, (yDp * density).toInt()),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun execute(
        name: String,
        args: String = "{}",
    ): JsonObject {
        val result = dispatch(name, args)
        assertTrue("$name: $result", result is ToolDispatchOutcome.Succeeded)
        return Json.parseToJsonElement((result as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
    }

    private fun dispatch(
        name: String,
        args: String = "{}",
    ): ToolDispatchOutcome {
        val descriptor = requireNotNull(c.toolPipeline.resolveLatest(name))
        val result =
            c.toolPipeline.dispatcher.dispatch(
                ToolDispatchRequest(
                    toolCallId = "$session-${count++}",
                    turnId = turn,
                    sessionId = session,
                    toolName = ToolName(name),
                    toolVersion = descriptor.version,
                    args = Json.parseToJsonElement(args).jsonObject,
                    mode = AgentMode.ACT,
                    profile = SafetyProfile.ADVANCED,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    dataOrigin = DataOrigin.WORKSPACE,
                    scope =
                        requireNotNull(
                            com.helix.app.automation.AutomationModule
                                .scopeFor(name, session),
                        ),
                    uiToken = "chat:$turn",
                ),
            )
        return result
    }
}
