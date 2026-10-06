package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit owner opt-in; no model, only the disposable test activity. */
class AccessibilityTouchReviewDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val center get() = AutomationPermissionCenter(app)
    private val session = "touch-review-${System.nanoTime()}"
    private val turn = "$session-turn"
    private var sequence = 0

    @Test fun fixedCoordinatesSeparateDeliveryFromFrameworkAcceptance(): Unit =
        runBlocking {
            val option = InstrumentationRegistry.getArguments().getString("helixTouchReview")
            assumeTrue(option != null)
            require(option == "true")
            require(android.os.Build.VERSION.SDK_INT >= 36)
            val profile = c.profileStore.profile
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val selection = MobileUseEvaluationSelection(app)
            c.storage.sessions.create(session, "Accessibility touch review", null, null, System.currentTimeMillis())
            c.storage.turns.start(turn, session, System.currentTimeMillis())
            try {
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                c.pluginService.setEnabled(plugin.id, true)
                selection.select(session, emptySet(), true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                await { center.serviceState() == AutomationServiceState.CONNECTED }
                val installer = InstrumentationRegistry.getArguments().getString("helixInstallerTouch")
                if (installer != null) {
                    require(installer == "true")
                    reviewInstaller()
                } else {
                    for (mode in listOf("normal", "no-semantics", "sensitive", "obscured")) {
                        reviewMode(mode)
                    }
                }
            } finally {
                selection.close()
                c.pluginService.setEnabled(plugin.id, plugin.enabled)
                c.profileStore.switchTo(profile)
                c.storage.deleteSessionPermanently(session)
            }
        }

    private fun reviewMode(mode: String) {
        app.startActivity(
            Intent()
                .setComponent(
                    ComponentName(
                        "${app.packageName}.test",
                        TouchReviewActivity::class.java.name,
                    ),
                ).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        SystemClock.sleep(1000)
        if (mode == "no-semantics") {
            val snapshot = dispatch("ui.snapshot", buildJsonObject {})
            assertFalse(snapshot.toString().contains("TOUCH REVIEW"))
        }
        val before = oracle()
        assertTrue(before.first > 0 && before.second > 0)
        val observation = dispatch("ui.device", buildJsonObject {})
        assertEquals("accessibility", observation["clickMatchBackend"]?.jsonPrimitive?.content)
        val frame = observation.getValue("frame").jsonPrimitive.content
        val gesture =
            dispatch(
                "ui.gesture",
                buildJsonObject {
                    put("frame", frame)
                    put(
                        "strokes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("durationMillis", 100)
                                    put(
                                        "points",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("x", before.first)
                                                    put("y", before.second)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        SystemClock.sleep(250)
        val after = oracle()
        println("TOUCH_REVIEW mode=$mode point=${before.first},${before.second} result=$gesture oracle=$after")
        assertEquals("SUCCEEDED", gesture["status"]?.jsonPrimitive?.content)
        val acceptsTouch = mode == "normal" || mode == "no-semantics"
        assertEquals(if (acceptsTouch) 1 else 0, after.third.first)
        assertTrue(after.third.second.contains("accepted=$acceptsTouch"))
    }

    private fun reviewInstaller() {
        val args = InstrumentationRegistry.getArguments()
        val x = requireNotNull(args.getString("helixTouchX")).toInt()
        val y = requireNotNull(args.getString("helixTouchY")).toInt()
        require(x in 0..1080 && y in 0..2400)
        val observation = dispatch("ui.device", buildJsonObject {})
        assertEquals("com.google.android.packageinstaller", observation["packageName"]?.jsonPrimitive?.content)
        assertEquals("accessibility", observation["clickMatchBackend"]?.jsonPrimitive?.content)
        val frame = observation.getValue("frame").jsonPrimitive.content
        val result =
            dispatch(
                "ui.gesture",
                buildJsonObject {
                    put("frame", frame)
                    put(
                        "strokes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("durationMillis", 100)
                                    put(
                                        "points",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("x", x)
                                                    put("y", y)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        SystemClock.sleep(1000)
        val installed =
            try {
                app.packageManager.getPackageInfo("com.helix.validation.installfixture", 0)
                true
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }
        println("TOUCH_INSTALLER point=$x,$y result=$result installed=$installed")
        assertFalse(installed)
    }

    private fun oracle(): Triple<Int, Int, Pair<Int, String>> =
        requireNotNull(
            app.contentResolver.query(
                Uri.parse("content://${app.packageName}.test.capability-state/touch"),
                null,
                null,
                null,
                null,
            ),
        ).use {
            check(it.moveToFirst())
            Triple(it.getInt(0), it.getInt(1), it.getInt(2) to it.getString(3))
        }

    private fun dispatch(
        name: String,
        args: JsonObject,
    ): JsonObject {
        val descriptor = requireNotNull(c.toolPipeline.resolveLatest(name))
        val result =
            c.toolPipeline.dispatcher.dispatch(
                ToolDispatchRequest(
                    toolCallId = "$session-${sequence++}",
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
        check(result is ToolDispatchOutcome.Succeeded) { "$result" }
        return Json.parseToJsonElement(result.result.payload).jsonObject
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(condition()) { "Accessibility not connected" }
    }
}
