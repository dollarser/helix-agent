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
@Suppress("TooManyFunctions", "LargeClass") // One owned journey retains setup/restoration and scoped evidence together.
class BackgroundDisplayDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center = AutomationPermissionCenter(app)
    private val session = "background-fixture-${UUID.randomUUID()}"
    private var turn = "$session-turn"
    private val typedText = "Hello background ${UUID.randomUUID().toString().take(8)}"
    private var sequence = 0

    private fun dispatch(
        name: String,
        args: JsonObject = buildJsonObject {},
    ): ToolDispatchOutcome {
        val callId = "$session-${++sequence}"
        if (name == "ui.gesture" && InstrumentationRegistry.getArguments().getString("helixInFlight") == "true") {
            // The crash case must include the production call journal, not only Dispatcher audit events.
            return container.chatService.dispatchToolCall(callId, turn, name, args.toString())
        }
        val descriptor = requireNotNull(container.toolPipeline.resolveLatest(name))
        return container.toolPipeline.dispatcher.dispatch(
            ToolDispatchRequest(
                toolCallId = callId,
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
        awaitStablePreviewWindow()
        val snapshot = readyFixtureSnapshot().getValue("nodes").jsonArray.map { it.jsonObject }
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

    private fun verifyHighResolutionPreview(value: com.helix.app.chat.DisplayExecutionUi) {
        val frame = awaitPreview(value, 720)
        assertEquals(720, frame.width)
        assertEquals(1280, frame.height)
        frame.recycle()
    }

    private fun awaitPreview(
        value: com.helix.app.chat.DisplayExecutionUi,
        width: Int,
    ): android.graphics.Bitmap {
        var frame =
            com.helix.app.automation.AutomationModule
                .backgroundPreview(value, width)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (frame == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            frame =
                com.helix.app.automation.AutomationModule
                    .backgroundPreview(value, width)
        }
        return requireNotNull(frame)
    }

    private fun verifyPreviewPageChanges(value: com.helix.app.chat.DisplayExecutionUi) {
        repeat(3) { cycle ->
            awaitStablePreviewWindow()
            output(
                "ui.click_match",
                buildJsonObject {
                    put("viewId", "android:id/button2")
                    put("text", "Open page")
                },
            )
            output(
                "ui.wait",
                buildJsonObject {
                    put("text", "BACKGROUND_PREVIEW_PAGE")
                    put("condition", "present")
                },
            )
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var visible = false
            while (!visible && SystemClock.elapsedRealtime() < deadline) {
                val frame =
                    com.helix.app.automation.AutomationModule
                        .backgroundPreview(value, 240)
                val pixel = frame?.getPixel(frame.width / 2, frame.height / 2)
                visible = pixel != null && android.graphics.Color.red(pixel) > 220 &&
                    android.graphics.Color.blue(pixel) > 220 && android.graphics.Color.green(pixel) < 40
                if (!visible) SystemClock.sleep(50)
            }
            assertTrue("New page pixels must actually reach the local preview", visible)
            awaitStablePreviewWindow()
            output("ui.click_match", buildJsonObject { put("text", "Back to fixture") })
            val snapshot = readyFixtureSnapshot()
            assertTrue(snapshot.toString(), snapshot.toString().contains("BACKGROUND_COUNT:"))
            awaitPreview(value, 240)
            report("BACKGROUND_PREVIEW_PAGE_CYCLE=$cycle;verified=true")
        }
    }

    private fun awaitStablePreviewWindow() {
        fun observe() =
            output(
                "ui.wait",
                buildJsonObject {
                    put("condition", "stable")
                    put("stableMillis", 500)
                    put("timeoutMillis", 3_000)
                },
            )
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var result = observe()
        // A page transition invalidates the observation target. Only refresh this read;
        // never repeat the click or accept an unstable/refused snapshot as success.
        while (result["status"]?.jsonPrimitive?.content == "TARGET_CHANGED" &&
            result["waitStatus"]?.jsonPrimitive?.content == "SNAPSHOT_REFUSED" &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
            result = observe()
        }
        assertEquals(result.toString(), "STABLE", result.getValue("waitStatus").jsonPrimitive.content)
    }

    @Suppress("LongMethod") // One production-boundary journey retains all restoration in finally.
    @Test
    fun productionToolsReuseDisplayAcrossTurnsAndReleaseItAfterGrantRevocation() {
        awaitExistingBackend()
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

        val plugin = container.pluginService.list().single { it.native?.pluginId == "mobile-use" }
        val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
        val fixturePackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val now = System.currentTimeMillis()
        val primaryHost = primaryHostForTest()
        container.storage.sessions.create(session, fixtureTitle(), null, null, now)
        container.storage.turns.start(turn, session, now)
        var displayId: Int? = null
        try {
            check(container.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            container.pluginService.setEnabled(plugin.id, true)
            awaitOwnerGrantedRootBackend()
            store.configureGlobal(setOf(fixturePackage), false)
            store.configureExecution(background = true, allowMoveFromMain = false)
            container.pluginService.catalog.select(session, plugin.id, true)
            container.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                now,
            )
            verifyEmptyTarget()
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
            val preview = awaitPreview(activePresentation, 240)
            assertEquals(240, preview.width)
            assertEquals(426, preview.height)
            verifyHighResolutionPreview(activePresentation)
            assertEquals(
                null,
                com.helix.app.automation.AutomationModule
                    .backgroundPreview(activePresentation.copy(generation = -1), 240),
            )
            assertEquals(
                null,
                com.helix.app.automation.AutomationModule.backgroundPreview(
                    activePresentation.copy(sessionId = "wrong"),
                    240,
                ),
            )
            verifyPreviewPageChanges(activePresentation)
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
            displayId = verifyRequestedDisconnect(displayId, fixturePackage)
            verifyCrossTurnRetention(displayId, activePresentation, fixturePackage)
            container.pluginService.catalog.select(session, plugin.id, false)
            val releaseDeadline = SystemClock.elapsedRealtime() + 5_000
            val manager = app.getSystemService(DisplayManager::class.java)
            while (manager.getDisplay(displayId) != null && SystemClock.elapsedRealtime() < releaseDeadline) {
                SystemClock.sleep(100)
            }
            assertEquals(null, manager.getDisplay(displayId))
            assertEquals(
                null,
                com.helix.app.automation.AutomationModule
                    .backgroundPreview(activePresentation, 240),
            )
            assertFalse(
                container.storage.auditEvents
                    .listByCorrelation("$session-1")
                    .isEmpty(),
            )

            fun hostPresentation() =
                container.chatService.displayExecutions.value.singleOrNull {
                    it.sessionId == session && it.turnId == activePresentation.turnId
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
            val disconnected =
                InstrumentationRegistry.getArguments().getString("helixDisconnect") == "true" &&
                    (
                        InstrumentationRegistry.getArguments().getString("helixRecovery") != "true" ||
                            InstrumentationRegistry.getArguments().getString("helixHostCleanup") == "true"
                    )
            val expected =
                if (disconnected) {
                    com.helix.app.chat.DisplayExecutionState.CONNECTION_LOST
                } else {
                    com.helix.app.chat.DisplayExecutionState.RELEASED
                }
            assertEquals(expected, requireNotNull(hostPresentation()).state)
            report("BACKGROUND_RELEASED=$displayId;dispatches=$sequence;HOST_PRESENTATION=$expected")
            if (InstrumentationRegistry.getArguments().getString("helixRecovery") ==
                "true"
            ) {
                awaitRecoveryObservation("revoked")
            }
        } finally {
            try {
                lines.setLines(MobileUseGrantStore.CONFIG_KEY, savedScope)
                lines.setLines(MobileUseGrantStore.EXECUTION_KEY, savedDisplay)
                container.pluginService.catalog.select(session, plugin.id, false)
                deleteResolvedFixture()
            } finally {
                try {
                    container.pluginService.setEnabled(plugin.id, plugin.enabled)
                } finally {
                    disconnectOwnerRootBackend()
                    primaryHost?.close()
                }
            }
        }
    }

    private fun fixtureTitle() =
        if (InstrumentationRegistry.getArguments().getString("helixInFlight") == "true") {
            val backend =
                if (InstrumentationRegistry.getArguments().getString("helixRootHelper") ==
                    "true"
                ) {
                    "Root"
                } else {
                    "ADB"
                }
            "$backend 手势中途断连（专项测试）"
        } else {
            "Background synthetic fixture"
        }

    private fun primaryHostForTest(): androidx.test.core.app.ActivityScenario<com.helix.app.MainActivity>? {
        if (InstrumentationRegistry.getArguments().getString("helixKeepScreenAwake") != "true") return null
        return androidx.test.core.app.ActivityScenario.launch(com.helix.app.MainActivity::class.java).onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun awaitExistingBackend() {
        val computerAdb = InstrumentationRegistry.getArguments().getString("helixAdbHelper") == "true"
        if (computerAdb) {
            awaitComputerAdbHelper()
        } else if (InstrumentationRegistry.getArguments().getString("helixRootHelper") == "true") {
            check(android.os.Build.VERSION.SDK_INT >= 31)
        } else {
            check(DeviceAccess.shizukuReady() && DeviceAccess.shizukuGranted()) {
                "Existing Shizuku authorization required; this fixture never requests it"
            }
        }
    }

    private fun deleteResolvedFixture() {
        val resolved =
            container.storage.turns
                .listBySession(session)
                .flatMap {
                    container.storage.toolCalls.listByTurn(it.id)
                }.all {
                    com.helix.core.model.ToolCallState
                        .valueOf(it.state)
                        .isTerminal
                }
        if (resolved) {
            container.storage.deleteSessionPermanently(session)
        } else {
            preserveInterruptedFixture()
            report("BACKGROUND_RETAINED_UNRESOLVED_SESSION=$session")
        }
    }

    private fun preserveInterruptedFixture() {
        if (InstrumentationRegistry.getArguments().getString("helixInFlight") != "true") return
        check(container.turnEngine?.liveExecution?.hasActive(session) != true)
        // This direct Dispatcher fixture has no AgentLoop owner. End only its dummy Turn;
        // preserve NEEDS_REVIEW calls/results without acknowledging or replaying their effects.
        val current = container.storage.turns.resolve(turn)
        if (!TurnState.valueOf(current.state).isTerminal) {
            container.storage.turns.updateState(
                current,
                TurnState.INTERRUPTED,
                current.stepCount,
                System.currentTimeMillis(),
                "CONTROLLED_HELPER_PROCESS_DEATH",
            )
        }
    }

    private fun verifyEmptyTarget() {
        val emptyTarget = output("ui.device")
        report("BACKGROUND_EMPTY_TARGET=$emptyTarget")
        if (InstrumentationRegistry.getArguments().getString("helixAdbHelper") == "true") {
            assertEquals("adb", emptyTarget.getValue("clickMatchBackend").jsonPrimitive.content)
        }
        if (InstrumentationRegistry.getArguments().getString("helixRootHelper") == "true") {
            assertEquals("root", emptyTarget.getValue("clickMatchBackend").jsonPrimitive.content)
        }
        assertEquals("TARGET_UNAVAILABLE", emptyTarget.getValue("status").jsonPrimitive.content)
        for (capability in listOf("backendReady", "semanticSupported", "screenshotSupported", "gestureSupported")) {
            assertEquals(capability, "true", emptyTarget.getValue(capability).jsonPrimitive.content)
        }
        assertFalse(emptyTarget.containsKey("frame"))
    }

    private fun verifyCrossTurnRetention(
        displayId: Int,
        activePresentation: com.helix.app.chat.DisplayExecutionUi,
        fixturePackage: String,
    ) {
        if (InstrumentationRegistry.getArguments().getString("helixDisconnect") == "true") return
        var completed = container.storage.turns.resolve(turn)
        for (state in listOf(
            TurnState.BUILDING_CONTEXT,
            TurnState.WAITING_MODEL,
            TurnState.RECEIVING_MODEL,
            TurnState.COMPLETED,
        )) {
            completed =
                container.storage.turns.updateState(
                    completed,
                    state,
                    completed.stepCount,
                    System.currentTimeMillis().takeIf { state.isTerminal },
                    null,
                )
        }
        SystemClock.sleep(1_500) // Exceed the helper's resource sweep interval.
        assertTrue(app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null)
        awaitPreview(activePresentation, 240)
        turn = "$session-next-turn"
        container.storage.turns.start(turn, session, System.currentTimeMillis())
        val continued = output("ui.device")
        assertEquals(displayId, continued.getValue("displayId").jsonPrimitive.int)
        assertEquals(fixturePackage, continued.getValue("packageName").jsonPrimitive.content)
        assertTrue(readyFixtureSnapshot().toString().contains(typedText))
        val cancelled = container.storage.turns.resolve(turn)
        val cancelling =
            container.storage.turns.updateState(
                cancelled,
                TurnState.CANCELLING,
                cancelled.stepCount,
                null,
                null,
            )
        container.storage.turns.updateState(
            cancelling,
            TurnState.CANCELLED,
            cancelling.stepCount,
            System.currentTimeMillis(),
            null,
        )
        SystemClock.sleep(1_500)
        assertTrue(app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null)
        awaitPreview(activePresentation, 240)
        val refused = dispatch("ui.device")
        if (refused is ToolDispatchOutcome.Succeeded) {
            val status =
                Json
                    .parseToJsonElement(
                        refused.result.payload,
                    ).jsonObject
                    .getValue("status")
                    .jsonPrimitive.content
            assertFalse("A cancelled Turn cannot observe through a retained display", status == "READY")
        }
        report("BACKGROUND_REUSED_ACROSS_TURNS=$displayId;completionAndCancellationRetainPreview=true")
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
            assertPersistedUnknown(outcome)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private fun assertPersistedUnknown(outcome: ToolDispatchOutcome) {
        assertTrue(outcome is ToolDispatchOutcome.ExecutionFailed && outcome.requiresReview && !outcome.sideEffectFree)
        val call =
            container.storage.toolCalls.listByTurn(turn).single {
                it.name == "ui.gesture" && Json
                    .parseToJsonElement(it.argsJson)
                    .jsonObject["strokes"]
                    ?.jsonArray
                    ?.any { stroke -> stroke.jsonObject["durationMillis"]?.jsonPrimitive?.int == 8000 } ==
                    true
            }
        assertEquals("NEEDS_REVIEW", call.state)
        assertEquals(
            "NEEDS_REVIEW",
            container.storage.toolResults
                .byToolCall(call.callId)
                ?.status,
        )
        report("BACKGROUND_INFLIGHT_UNKNOWN call=${call.callId};state=${call.state};autoReplay=false")
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

    private fun verifyRequestedDisconnect(
        displayId: Int,
        fixturePackage: String,
    ): Int {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("helixDisconnect") != "true") return displayId
        return when {
            arguments.getString("helixInFlight") == "true" -> {
                verifyInFlightDisconnect(displayId)
                displayId
            }

            arguments.getString("helixRecovery") == "true" -> {
                verifyRetainedHelperRecovery(displayId, fixturePackage)
            }

            else -> {
                verifyRealHelperDisconnect(displayId)
                displayId
            }
        }
    }

    private fun verifyRetainedHelperRecovery(
        displayId: Int,
        fixturePackage: String,
    ): Int {
        val oldSnapshot = readyFixtureSnapshot()
        val oldToken =
            oldSnapshot
                .getValue("nodes")
                .jsonArray
                .map { it.jsonObject }
                .single { it["editable"]?.jsonPrimitive?.content == "true" }
                .getValue("token")
        report("BACKGROUND_DISCONNECT_READY display=$displayId")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (requestedHelperConnected() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
        }
        assertTrue(app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null)
        reconnectRequestedHelper()
        assertEquals(displayId, output("ui.device").getValue("displayId").jsonPrimitive.int)
        readyFixtureSnapshot()
        val stale =
            dispatch(
                "ui.set_text",
                buildJsonObject {
                    put("token", oldToken)
                    put("text", "MUST_NOT_WRITE")
                },
            )
        assertTrue(stale is ToolDispatchOutcome.ExecutionFailed && stale.sideEffectFree)
        val presentation =
            container.chatService.displayExecutions.value.single {
                it.sessionId == session &&
                    it.turnId == turn
            }
        awaitPreview(presentation, 240)
        report("BACKGROUND_ADOPTED_ORIGINAL=$displayId")
        awaitRecoveryObservation("adopted")
        verifyPrimaryMigrationRefusal(displayId, fixturePackage)
        return verifyLostScreenMigration(displayId, fixturePackage)
    }

    private fun verifyPrimaryMigrationRefusal(
        displayId: Int,
        fixturePackage: String,
        stage: String = "primaryRefused",
    ) {
        assertTrue(app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null)
        val refused = dispatch("ui.launch", buildJsonObject { put("packageName", fixturePackage) })
        assertTrue(
            "Must preserve the main-display migration setting: $refused",
            refused is ToolDispatchOutcome.ExecutionFailed && refused.sideEffectFree &&
                refused.detail.startsWith("MAIN_DISPLAY_IN_USE"),
        )
        awaitRecoveryObservation(stage)
        assertEquals(displayId, awaitReadyRecoveryDevice().getValue("displayId").jsonPrimitive.int)
    }

    private fun verifyLostScreenMigration(
        displayId: Int,
        fixturePackage: String,
    ): Int {
        // Remove only this synthetic display through the app-owned system token, simulating platform loss.
        val lease = releaseSyntheticHostDisplay(displayId)
        val removed = SystemClock.elapsedRealtime() + 5000
        while (app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null &&
            SystemClock.elapsedRealtime() < removed
        ) {
            SystemClock.sleep(50)
        }
        assertEquals(null, app.getSystemService(DisplayManager::class.java).getDisplay(displayId))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "BACKGROUND_ORIGINAL_REMOVED display=$displayId\n")
            },
        )
        awaitRecoveryObservation("removed")
        val empty = output("ui.device")
        assertEquals("TARGET_UNAVAILABLE", empty.getValue("status").jsonPrimitive.content)
        val replacement =
            lease.javaClass
                .getDeclaredField("displayId")
                .apply { isAccessible = true }
                .getInt(lease)
        assertTrue(replacement > 0 && replacement != displayId)
        report("BACKGROUND_EMPTY_REPLACEMENT=$replacement")
        awaitRecoveryObservation("empty")
        assertEquals(
            "DISPATCHED",
            output("ui.launch", buildJsonObject { put("packageName", fixturePackage) })
                .getValue("status")
                .jsonPrimitive.content,
        )
        awaitRecoveryObservation("migrationDispatched")
        assertEquals(replacement, awaitReadyRecoveryDevice().getValue("displayId").jsonPrimitive.int)
        readyFixtureSnapshot()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "BACKGROUND_TASK_MIGRATED display=$replacement\n")
            },
        )
        awaitRecoveryObservation("migrated")
        verifyPrimaryMigrationRefusal(replacement, fixturePackage, "recoveredPrimaryRefused")
        stopHelperBeforeHostCleanup(replacement)
        return replacement
    }

    private fun stopHelperBeforeHostCleanup(displayId: Int) {
        if (InstrumentationRegistry.getArguments().getString("helixHostCleanup") == "true") {
            report("BACKGROUND_FINAL_HELPER_STOP")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (requestedHelperConnected() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertFalse(requestedHelperConnected())
            assertTrue(app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null)
            awaitRecoveryObservation("helperGone")
        }
    }

    private fun requestedHelperConnected() =
        if (InstrumentationRegistry.getArguments().getString("helixRootHelper") == "true") {
            DeviceAccess.root("mobile-use")?.connectedBinder() != null
        } else {
            com.helix.app.automation.AutomationModule.computerAdbBackend
                ?.state() ==
                com.helix.extensions.mobileuse.automation.AutomationBackendState.READY
        }

    private fun reconnectRequestedHelper() {
        if (InstrumentationRegistry.getArguments().getString("helixRootHelper") == "true") {
            awaitOwnerGrantedRootBackend()
        } else {
            awaitComputerAdbHelper()
        }
    }

    private fun awaitReadyRecoveryDevice(): JsonObject {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var observed = output("ui.device")
        while (observed["status"]?.jsonPrimitive?.content != "READY" && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            observed = output("ui.device")
        }
        assertEquals(
            "Recovery target did not become ready: $observed",
            "READY",
            observed["status"]?.jsonPrimitive?.content,
        )
        return observed
    }

    private fun awaitRecoveryObservation(stage: String) {
        val marker = app.cacheDir.resolve("recovery-observe-${UUID.randomUUID()}")
        marker.writeText(stage)
        try {
            report("RECOVERY_OBSERVE stage=$stage marker=${marker.name}")
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (marker.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(!marker.exists()) { "Host did not record recovery evidence: $stage" }
        } finally {
            marker.delete()
        }
    }

    /** Test-only reflection targets the exact fixture display; no production recovery endpoint is exposed. */
    private fun releaseSyntheticHostDisplay(displayId: Int): Any {
        val module = com.helix.app.automation.AutomationModule
        val runtime =
            module.javaClass
                .getDeclaredField("runtime")
                .apply { isAccessible = true }
                .get(module)
                as com.helix.extensions.mobileuse.MobileUsePlugin
        val center = runtime.permissionCenter
        val leases =
            center.javaClass
                .getDeclaredField(
                    "displayLeases",
                ).apply { isAccessible = true }
                .get(center) as Map<*, *>
        val lease =
            leases.values.filterNotNull().single {
                it.javaClass
                    .getDeclaredField("displayId")
                    .apply { isAccessible = true }
                    .getInt(it) == displayId
            }

        fun field(name: String) =
            lease.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(lease)
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken("android.hardware.display.IDisplayManager")
            data.writeStrongBinder(field("token") as android.os.IBinder)
            assertTrue((field("control") as android.os.IBinder).transact(field("releaseCode") as Int, data, reply, 0))
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
        return lease
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
        while (!disconnected() &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
        }
        assertTrue("Expected actual original-helper Binder death; no automatic action replay", disconnected())
        assertTrue(
            "Host-owned display must survive helper death",
            app.getSystemService(DisplayManager::class.java).getDisplay(displayId) != null,
        )
        if (InstrumentationRegistry.getArguments().getString("helixFocusRecovery") == "true") {
            awaitHostFocusVerification()
        }
    }

    /** Keep the real host alive until external focus verification; instrumentation otherwise force-stops it. */
    private fun awaitHostFocusVerification() {
        val marker = app.cacheDir.resolve("focus-recovery-${UUID.randomUUID()}")
        try {
            report("BACKGROUND_FOCUS_WAIT marker=${marker.name}")
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (!marker.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Host did not confirm original task/focus recovery", marker.exists())
        } finally {
            marker.delete()
        }
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
