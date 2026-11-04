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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
@Suppress("TooManyFunctions") // One ownership journey shares setup/restoration across device boundary checks.
class BackgroundDisplayDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center = AutomationPermissionCenter(app)
    private val session = "background-fixture-${UUID.randomUUID()}"
    private val turn = "$session-turn"
    private val typedText = "Hello background ${UUID.randomUUID().toString().take(8)}"
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

    private fun verifyVerticalScroll() {
        val snapshot = output("ui.snapshot").getValue("nodes").jsonArray.map { it.jsonObject }
        val container = snapshot.single { it["viewId"]?.jsonPrimitive?.content == "android:id/list" }
        assertTrue(container.getValue("scrollDirections").jsonArray.any { it.jsonPrimitive.content == "down" })

        fun firstVisibleRow(nodes: List<JsonObject>): Int {
            val viewport = container.getValue("bounds").jsonObject
            val top =
                viewport
                    .getValue("top")
                    .jsonPrimitive.content
                    .toInt()
            val bottom =
                viewport
                    .getValue("bottom")
                    .jsonPrimitive.content
                    .toInt()
            return nodes
                .mapNotNull { node ->
                    val text = node["text"]?.jsonPrimitive?.content.orEmpty()
                    val index = text.removePrefix("SCROLL_ROW:").toIntOrNull()
                    val bounds = node.getValue("bounds").jsonObject
                    val rowTop =
                        bounds
                            .getValue("top")
                            .jsonPrimitive.content
                            .toInt()
                    val rowBottom =
                        bounds
                            .getValue("bottom")
                            .jsonPrimitive.content
                            .toInt()
                    index?.takeIf { text.startsWith("SCROLL_ROW:") && minOf(rowBottom, bottom) > maxOf(rowTop, top) }
                }.minOrNull() ?: error("No visible synthetic scroll rows: $nodes")
        }
        val before = firstVisibleRow(snapshot)
        output(
            "ui.scroll",
            buildJsonObject {
                put("token", container.getValue("token").jsonPrimitive.content)
                put("direction", "down")
            },
        )
        val deadline = SystemClock.elapsedRealtime() + 3000
        var after = firstVisibleRow(output("ui.snapshot").getValue("nodes").jsonArray.map { it.jsonObject })
        while (after == before && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            after = firstVisibleRow(output("ui.snapshot").getValue("nodes").jsonArray.map { it.jsonObject })
        }
        assertTrue("vertical content must move, not switch a horizontal pager: $before -> $after", after > before)
        report("BACKGROUND_VERTICAL_SCROLL=$before->$after")
    }

    private fun awaitPreview(value: com.helix.app.chat.DisplayExecutionUi): android.graphics.Bitmap {
        var frame =
            com.helix.app.automation.AutomationModule
                .backgroundPreview(value)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (frame == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            frame =
                com.helix.app.automation.AutomationModule
                    .backgroundPreview(value)
        }
        return requireNotNull(frame)
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
            report("BACKGROUND_DISPLAY=$displayId;focus=${device["independentFocus"]}")
            val activePresentation =
                container.chatService.displayExecutions.value.single {
                    it.sessionId == session &&
                        it.turnId == turn
                }
            assertEquals(com.helix.app.chat.DisplayExecutionState.ACTIVE, activePresentation.state)
            assertEquals("com.helix.agent.developer.test", activePresentation.lastObservedPackage)
            val preview = awaitPreview(activePresentation)
            assertEquals(240, preview.width)
            assertEquals(426, preview.height)
            assertEquals(
                null,
                com.helix.app.automation.AutomationModule
                    .backgroundPreview(activePresentation.copy(generation = -1)),
            )
            assertEquals(
                null,
                com.helix.app.automation.AutomationModule.backgroundPreview(
                    activePresentation.copy(sessionId = "wrong"),
                ),
            )
            verifyVerticalScroll()
            val observed = readyFixtureSnapshot()
            val initialCount =
                requireNotNull(Regex("BACKGROUND_COUNT:(\\d+)").find(observed.toString()))
                    .groupValues[1]
                    .toInt()
            val nodes = observed.getValue("nodes").jsonArray.map { it.jsonObject }
            val input = nodes.single { it["editable"]?.jsonPrimitive?.content == "true" }
            val original = input.getValue("token").jsonPrimitive.content
            typeAfterReadOnlyRefreshIfStale(observed, displayId)
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
            assertTrue(after, after.contains("BACKGROUND_COUNT:${initialCount + 1}"))
            assertTrue(after, after.contains(typedText))
            report("BACKGROUND_TASK_ID=" + Regex("BACKGROUND_TASK_ID:(\\d+)").find(after)!!.groupValues[1])
            device = output("ui.device")
            val frame = device.getValue("frame").jsonPrimitive.content
            val capture = output("ui.screenshot", buildJsonObject { put("frame", frame) })
            assertEquals("SAVED", capture.getValue("status").jsonPrimitive.content)
            report(
                "BACKGROUND_CAPTURE reference=${capture.getValue("reference").jsonPrimitive.content};" +
                    "sha256=${capture.getValue("sha256").jsonPrimitive.content};" +
                    "width=${capture.getValue("width")};height=${capture.getValue("height")}",
            )
            if (InstrumentationRegistry.getArguments().getString("helixDisconnect") == "true") {
                if (InstrumentationRegistry.getArguments().getString("helixInFlight") == "true") {
                    verifyInFlightDisconnect(displayId)
                } else {
                    verifyRealHelperDisconnect(displayId)
                }
            }
            val row = container.storage.turns.resolve(turn)
            container.storage.turns.updateState(row, TurnState.CANCELLING, row.stepCount, null, null)
            val releaseDeadline = SystemClock.elapsedRealtime() + 5_000
            val manager = app.getSystemService(DisplayManager::class.java)
            while (manager.getDisplay(displayId) != null && SystemClock.elapsedRealtime() < releaseDeadline) {
                SystemClock.sleep(100)
            }
            assertEquals(null, manager.getDisplay(displayId))
            assertEquals(
                null,
                com.helix.app.automation.AutomationModule
                    .backgroundPreview(activePresentation),
            )
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
            report("BACKGROUND_RELEASED=$displayId;dispatches=$sequence;HOST_PRESENTATION=$expected")
        } finally {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, savedScope)
            lines.setLines(MobileUseGrantStore.EXECUTION_KEY, savedDisplay)
            container.pluginService.catalog.select(session, plugin.id, false)
            container.storage.deleteSessionPermanently(session)
            container.pluginService.setEnabled(plugin.id, plugin.enabled)
            container.profileStore.switchTo(profile)
        }
    }

    private fun report(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "$value\n")
            },
        )
    }

    private fun verifyInFlightDisconnect(displayId: Int) {
        val fresh = output("ui.device")
        val frame = fresh.getValue("frame").jsonPrimitive.content
        val bounds = fresh.getValue("windowBounds").jsonObject
        val x = (bounds.getValue("left").jsonPrimitive.int + bounds.getValue("right").jsonPrimitive.int) / 2
        val y = (bounds.getValue("top").jsonPrimitive.int + bounds.getValue("bottom").jsonPrimitive.int) / 2
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply {
                    putString("stream", "BACKGROUND_INFLIGHT_START uptime=${SystemClock.uptimeMillis()}\n")
                },
            )
            val future =
                executor.submit<ToolDispatchOutcome> {
                    dispatch("ui.gesture", longGesture(frame, x, y))
                }
            SystemClock.sleep(500)
            if (future.isDone) {
                val early = future.get()
                val code =
                    when (early) {
                        is ToolDispatchOutcome.ExecutionFailed -> {
                            "${early.code.name}:${Regex("[A-Z][A-Z0-9_]*").find(early.detail)?.value}"
                        }

                        is ToolDispatchOutcome.Denied -> {
                            early.code.name
                        }

                        else -> {
                            early.javaClass.simpleName
                        }
                    }
                error("Gesture ended before host DOWN checkpoint: $code")
            }
            // The host waits for the fixture's actual DOWN on this display before killing the helper.
            verifyRealHelperDisconnect(displayId)
            val outcome = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(
                "In-flight disconnect must not report success: $outcome",
                outcome is ToolDispatchOutcome.ExecutionFailed,
            )
            assertTrue(
                "Dispatched effect must remain unknown: $outcome",
                outcome.toString().contains("OUTCOME_UNKNOWN"),
            )
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private fun longGesture(
        frame: String,
        x: Int,
        y: Int,
    ) = buildJsonObject {
        put("frame", frame)
        put(
            "strokes",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("durationMillis", 8000)
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

    private fun typeAfterReadOnlyRefreshIfStale(
        initial: JsonObject,
        expectedDisplay: Int,
    ) {
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
                        put("text", typedText)
                    },
                )
            if (result is ToolDispatchOutcome.Succeeded) return
            check(
                result is ToolDispatchOutcome.ExecutionFailed && result.sideEffectFree && !result.requiresReview &&
                    (result.detail.startsWith("STALE_TOKEN") || result.detail.startsWith("TARGET_CHANGED")),
            ) { "Unexpected input outcome; do not replay: $result" }
            println("BACKGROUND_READ_ONLY_REFRESH_AFTER_STALE")
            SystemClock.sleep(300)
            val fresh = output("ui.device")
            assertEquals(expectedDisplay, fresh.getValue("displayId").jsonPrimitive.int)
            assertEquals(
                InstrumentationRegistry.getInstrumentation().context.packageName,
                fresh.getValue("packageName").jsonPrimitive.content,
            )
            observed = readyFixtureSnapshot()
        }
        error("Background target remained stale after bounded read-only refreshes")
    }
}
