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

/** Bounded opt-in; no account, network, app install or permission grant to a third-party application. */
class PrivilegedNavigationDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    @Suppress("LongMethod")
    fun shizukuNavigationAndNotificationChoiceWithoutAccessibility() {
        require(InstrumentationRegistry.getArguments().getString("helixPrivilegedNavigation") == "true")
        assertEquals(
            0,
            android.provider.Settings.Secure
                .getInt(app.contentResolver, "accessibility_enabled", 0),
        )
        val session = "privileged-navigation-${System.nanoTime()}"
        val turn = "$session-turn"
        val profile = c.profileStore.profile
        val selection = MobileUseEvaluationSelection(app)
        c.storage.sessions.create(session, "Privileged navigation fixture", null, null, System.currentTimeMillis())
        c.storage.turns.start(turn, session, System.currentTimeMillis())
        try {
            c.profileStore.switchTo(SafetyProfile.ADVANCED)
            selection.select(session, emptySet(), true)
            c.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                System.currentTimeMillis(),
            )
            var count = 0

            fun execute(
                name: String,
                args: String = "{}",
            ): JsonObject {
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
                println("NAVIGATION:$name:$result")
                assertTrue("$name: $result", result is ToolDispatchOutcome.Succeeded)
                return Json.parseToJsonElement((result as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
            }
            execute("ui.launch", """{"packageName":"${app.packageName}"}""")
            SystemClock.sleep(800)
            execute("ui.home")
            SystemClock.sleep(800)
            execute("ui.system", """{"action":"recents"}""")
            SystemClock.sleep(800)
            execute("ui.back")
            app.startActivity(
                Intent()
                    .setComponent(
                        ComponentName(
                            "${app.packageName}.test",
                            AutomationEvaluationActivity::class.java.name,
                        ),
                    ).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    ).putExtra("notificationPrompt", true),
            )
            SystemClock.sleep(1200)
            val snapshot = execute("ui.snapshot")
            assertEquals("shizuku", snapshot.getValue("backend").jsonPrimitive.content)
            val deny =
                snapshot.getValue("nodes").jsonArray.map { it.jsonObject }.single {
                    it["viewId"]?.jsonPrimitive?.content?.endsWith(":id/permission_deny_button") == true
                }
            execute("ui.click", """{"token":"${deny.getValue("token").jsonPrimitive.content}"}""")
            SystemClock.sleep(400)
            val uri = android.net.Uri.parse("content://${app.packageName}.test.capability-state/result")
            app.contentResolver.query(uri, null, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(2))
            }
            val observed = execute("ui.device")
            assertEquals("shizuku", observed.getValue("clickMatchBackend").jsonPrimitive.content)
            println("PRIVILEGED_NAVIGATION_PASSED")
        } finally {
            selection.close()
            c.storage.deleteSessionPermanently(session)
            c.profileStore.switchTo(profile)
        }
    }
}
