package com.helix.extensions.mobileuse

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.TurnState
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentationFactory
import com.helix.extensions.mobileuse.automation.AutomationServiceController
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationStopReason
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.extensions.mobileuse.automation.HelixAccessibilityService
import com.helix.extensions.plugin.PluginTaskHost
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MobileUseOverlayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            serviceInfo =
                serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
        }
    private val context = instrumentation.targetContext
    private val identity = PluginTaskIdentity("overlay-fixture-conversation", "overlay-fixture-turn")
    private val returned = AtomicReference<PluginTaskIdentity?>()
    private val conversationVisible =
        java.util.concurrent.atomic
            .AtomicBoolean()
    private var activeRegistration: AutoCloseable? = null
    private var imageDisclosurePending = false

    @Test fun imageDisclosureWaitIsShownWithoutBecomingToolApprovalOrCancellation() {
        imageDisclosurePending = true
        try {
            withOverlay { overlay, stopped ->
                overlay.bind(call())
                val statusLabel = context.getString(R.string.mobile_overlay_image_confirmation)
                waitUntil {
                    var shown = false
                    instrumentation.runOnMainSync {
                        fun matches(view: android.view.View): Boolean =
                            (
                                view is android.widget.TextView &&
                                    view.text.toString() == statusLabel
                            ) ||
                                (
                                    view is android.view.ViewGroup &&
                                        (0 until view.childCount).any {
                                            matches(view.getChildAt(it))
                                        }
                                )
                        shown =
                            android.view.inspector.WindowInspector
                                .getGlobalWindowViews()
                                .any(::matches)
                    }
                    shown
                }
                waitUntil { controlBounds(R.string.mobile_overlay_view_chat) != null }
                val bounds = requireNotNull(controlBounds(R.string.mobile_overlay_view_chat))
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                waitUntil { returned.get() != null }
                assertEquals(identity, returned.get())
                assertNull(stopped.get())
                assertTrue(overlay.executionAllowed())
            }
        } finally {
            imageDisclosurePending = false
        }
    }

    @Test fun stoppedWaitRetainsTheRealGestureAndPresentationUntilItsAndroidCallback() {
        withOverlay { overlay, _ ->
            val reference = requireNotNull(AutomationRuntimePresentationFactory.borrow())
            val service =
                com.helix.extensions.mobileuse.automation.AutomationServiceController
                    .overlayContext() as HelixAccessibilityService
            val physical = com.helix.extensions.mobileuse.automation.AutomationServiceController.physicalInput
            val ticket = requireNotNull(physical.acquire(reference.retain()))
            physical.attach(ticket, requireNotNull(overlay.hideForOperation(call())))
            val done = java.util.concurrent.CountDownLatch(1)
            val path = android.graphics.Path().apply { moveTo(dp(40).toFloat(), dp(90).toFloat()) }
            val gesture =
                android.accessibilityservice.GestureDescription
                    .Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 1500))
                    .build()
            var accepted = false
            try {
                instrumentation.runOnMainSync {
                    accepted =
                        service.dispatchGesture(
                            gesture,
                            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(description: android.accessibilityservice.GestureDescription) {
                                    physical.release(ticket)
                                    done.countDown()
                                }

                                override fun onCancelled(description: android.accessibilityservice.GestureDescription) {
                                    physical.release(ticket)
                                    done.countDown()
                                }
                            },
                            null,
                        )
                }
                assertTrue("Fixture gesture must really be submitted to Android", accepted)
                requireNotNull(activeRegistration).close()
                reference.close() // Original caller finished waiting; physical gesture remains in progress.
                assertFalse(reference.accepting)
                assertTrue("Cancellation/retirement cannot release Android's original ticket", physical.isOccupied())
                assertNull(AutomationRuntimePresentationFactory.borrow())
                assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(physical.isOccupied())
                assertFalse("Final callback must complete actual presentation cleanup", overlay.bind(call()))
            } finally {
                if (!accepted) physical.release(ticket)
                reference.close()
                if (accepted) assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test fun translucentStatusPassesTouchesAndPhysicalSuppressionRestoresPixels() {
        withOverlay { overlay, _ ->
            val baseline = awaitScreenshot { Color.red(it.getPixel(dp(40), dp(90))) == 240 }
            try {
                assertTrue(overlay.bind(call()))
                waitUntil { hasControl() }
                val overlayIds =
                    automation.windows
                        .filter {
                            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        }.map { it.id }
                // The passive status view opts out of accessibility; the control window remains discoverable.
                assertEquals(1, overlayIds.size)
                instrumentation.runOnMainSync {
                    overlayIds.forEach { assertTrue(overlay.ownsWindow(it)) }
                }
                val shown = awaitScreenshot { hasBlendedPixel(baseline, it) }
                try {
                    assertTrue("Status must visibly blend with the fixture", hasBlendedPixel(baseline, shown))
                } finally {
                    shown.recycle()
                }
                val lease = overlay.hideForOperation(call())
                assertNotNull("Overlay must hide before capture", lease)
                try {
                    assertFalse(hasControl())
                    val hidden = requireNotNull(automation.takeScreenshot())
                    try {
                        if (hasBlendedPixel(baseline, hidden)) saveFailurePixels(baseline, hidden)
                        assertFalse("Hidden status must not contaminate pixels", hasBlendedPixel(baseline, hidden))
                    } finally {
                        hidden.recycle()
                    }
                } finally {
                    lease?.close()
                }
                waitUntil { hasControl() }
                // Check pixels before injecting a touch: system touch visualization can affect
                // this same pixel region. Preserve the independent pass-through assertion.
                val clicks = OverlayFixtureActivity.clicks.get()
                shell("input tap ${dp(40)} ${dp(90)}")
                waitUntil { OverlayFixtureActivity.clicks.get() > clicks }
                assertNull(
                    overlay.hideForOperation(
                        call().copy(
                            cancel =
                                object : CancelSignal {
                                    override fun isCancelled() = true
                                },
                        ),
                    ),
                )
                waitUntil { hasControl() }
            } finally {
                baseline.recycle()
            }
        }
    }

    @Test fun notificationStopWorksWhileControlsAreHidden() {
        withOverlay { overlay, stopped ->
            assertTrue(overlay.bind(call()))
            waitUntil { hasControl() }
            val lease = requireNotNull(overlay.hideForOperation(call()))
            try {
                assertFalse(hasControl())
                overlay.stopBoundTask(PluginTaskIdentity("old-conversation", "old-turn"))
                assertNull(stopped.get())
                assertTrue(overlay.executionAllowed())
                overlay.stopBoundTask(identity)
                assertEquals(identity, stopped.get())
                assertFalse(overlay.executionAllowed())
                assertFalse(overlay.bind(call()))
            } finally {
                lease.close()
            }
        }
    }

    @Test fun semanticObservationsRetainWindowIdentityAndExcludeOverlayNodes() {
        withOverlay { overlay, _ ->
            assertTrue(overlay.bind(call()))
            waitUntil { hasControl() }
            val controller = AutomationServiceController
            controller.replaceAllowlist(context, setOf(context.packageName))
            assertNotNull(controller.startUserSession(context, setOf(context.packageName)).session)
            var ids = emptySet<Int>()
            waitUntil {
                instrumentation.runOnMainSync {
                    ids =
                        automation.windows
                            .filter { overlay.ownsWindow(it.id) }
                            .map { it.id }
                            .toSet()
                }
                ids.isNotEmpty()
            }
            assertTrue(ids.isNotEmpty())
            val executor =
                MobileUsePresentedExecutor(
                    object : ToolExecutor {
                        override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                            val result = controller.snapshot()
                            assertEquals(AutomationSnapshotStatus.SUCCESS, result.status)
                            val snapshot = requireNotNull(result.snapshot)
                            assertEquals(context.packageName, snapshot.packageName)
                            assertFalse(snapshot.windowId in ids)
                            assertTrue(snapshot.nodes.any { it.contentDescription == "Overlay observation fixture" })
                            assertTrue(hasControl())
                            return ToolExecutorResult.Completed(JsonObject(emptyMap()))
                        }
                    },
                    { AutomationRuntimePresentationFactory.borrow() },
                    retainOverlay = true,
                ) { null }
            try {
                repeat(10) {
                    assertTrue(executor.execute(call()) is ToolExecutorResult.Completed)
                    assertEquals(
                        ids,
                        automation.windows
                            .filter { overlay.ownsWindow(it.id) }
                            .map { it.id }
                            .toSet(),
                    )
                    Thread.sleep(60)
                }
            } finally {
                controller.stop(AutomationStopReason.USER_STOP)
            }
        }
    }

    @Test fun nestedCaptureSuppressionKeepsPixelsClearUntilRealShortGestureCompletes() {
        withOverlay { overlay, _ ->
            val baseline = awaitScreenshot { Color.red(it.getPixel(dp(40), dp(90))) == 240 }
            try {
                overlay.bind(call())
                waitUntil { hasControl() }
                val outer = requireNotNull(overlay.hideForOperation(call()))
                try {
                    repeat(4) { assertNestedCapture(overlay, baseline) }
                    val controller = AutomationServiceController
                    controller.replaceAllowlist(context, setOf(context.packageName))
                    val session =
                        requireNotNull(
                            controller.startUserSession(context, setOf(context.packageName)).session,
                        )
                    val service = controller.overlayContext() as HelixAccessibilityService
                    val frame = requireNotNull(service.deviceAccess.observe(session).frame)
                    val image = service.deviceAccess.screenshot(session, frame.token, call())
                    assertEquals("SAVED", image.status)
                    val png = requireNotNull(image.png)
                    val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size))
                    try {
                        assertEquals(240, Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
                    } finally {
                        bitmap.recycle()
                    }
                    val clicks = OverlayFixtureActivity.clicks.get()
                    val result =
                        service.deviceAccess.gesture(
                            session,
                            frame.token,
                            listOf(
                                AutomationStroke(listOf(AutomationPoint(dp(40).toFloat(), dp(90).toFloat())), 0, 100),
                            ),
                            call(),
                        )
                    assertEquals(AutomationActionStatus.SUCCEEDED, result.status)
                    waitUntil { OverlayFixtureActivity.clicks.get() == clicks + 1 }
                    assertFalse(hasControl())
                } finally {
                    outer.close()
                }
                waitUntil { hasControl() }
            } finally {
                baseline.recycle()
            }
        }
    }

    private fun assertNestedCapture(
        overlay: MobileUseOverlay,
        baseline: Bitmap,
    ) {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        requireNotNull(overlay.hideForOperation(call())).use {
            println("NESTED_HIDE_NANOS=${android.os.SystemClock.elapsedRealtimeNanos() - started}")
            val pixels = requireNotNull(automation.takeScreenshot())
            try {
                assertFalse("Confirmed hide must exclude overlay pixels", hasBlendedPixel(baseline, pixels))
                assertFalse(hasControl())
            } finally {
                pixels.recycle()
            }
        }
        overlay.bind(call())
    }

    private fun saveFailurePixels(
        before: Bitmap,
        after: Bitmap,
    ) {
        for ((name, image) in listOf("overlay-baseline.png" to before, "overlay-hidden.png" to after)) {
            java.io.File(context.cacheDir, name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun nativeTakeoverStopsOnlyBoundTaskAndRejectsFurtherBinding() {
        withOverlay { overlay, stopped ->
            overlay.bind(call())
            waitUntil { hasControl() }
            val bounds = controlBounds() ?: error("Control missing")
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            waitUntil { stopped.get() != null }
            assertEquals(identity, stopped.get())
            assertFalse(overlay.executionAllowed())
            assertFalse(overlay.bind(call()))
        }
    }

    @Test fun returningOnlyNavigatesAndServiceCloseRemovesControls() {
        withOverlay { overlay, stopped ->
            overlay.bind(call())
            waitUntil { controlBounds(R.string.mobile_overlay_view_chat) != null }
            val bounds = requireNotNull(controlBounds(R.string.mobile_overlay_view_chat))
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            waitUntil { returned.get() != null }
            assertEquals(identity, returned.get())
            assertNull(stopped.get())
            assertTrue(overlay.executionAllowed())
            overlay.close()
            waitUntil { !hasControl() }
            assertFalse(overlay.bind(call()))
        }
    }

    private fun overlayGrantMode(packageName: String): String =
        Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)")
            .find(shell("cmd appops get $packageName SYSTEM_ALERT_WINDOW"))
            ?.groupValues
            ?.get(1) ?: "default"

    @Test fun applicationOverlayWorksWithoutHelixAccessibilityAndRemovesAfterRevocation() {
        val app = instrumentation.context.applicationContext
        val component = ComponentName(context, HelixAccessibilityService::class.java).flattenToString()
        val original = shell("settings get secure enabled_accessibility_services").trim()
        val remaining =
            original
                .split(
                    ':',
                ).filter { it != component && it != "null" && it.isNotBlank() }
                .joinToString(":")
        val originalMode = overlayGrantMode(app.packageName)
        val overlay =
            MobileUseOverlay(
                app,
                object : PluginTaskHost {
                    override val conversationVisible: Boolean get() =
                        this@MobileUseOverlayDeviceTest
                            .conversationVisible
                            .get()

                    override fun reviewApproval(
                        task: PluginTaskIdentity,
                        approvalId: String,
                        approve: Boolean,
                    ) = Unit

                    override fun snapshot(task: PluginTaskIdentity) =
                        PluginTaskSnapshot(
                            TurnState.WAITING_MODEL,
                            awaitingDataDisclosure = imageDisclosurePending,
                        )

                    override fun requestStop(task: PluginTaskIdentity) = Unit

                    override fun openConversation(task: PluginTaskIdentity) = Unit
                },
                surface = { app.takeIf { android.provider.Settings.canDrawOverlays(it) } },
            ) { true }
        try {
            if (remaining.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $remaining")
            }
            shell("cmd appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            waitUntil { android.provider.Settings.canDrawOverlays(app) }
            context.startActivity(
                Intent(context, OverlayFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertEquals(
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                MobileUseOverlayWindows(app, {}, {}, { _, _ -> }).parameters(false).type,
            )
            assertTrue(overlay.bind(call()))
            waitUntil { hasControl() }
            val hidden = requireNotNull(overlay.hideForOperation(call()))
            assertFalse(hasControl())
            hidden.close()
            waitUntil { hasControl() }
            shell("cmd appops set ${app.packageName} SYSTEM_ALERT_WINDOW deny")
            waitUntil { !hasControl() }
            // Lack of a presentation grant does not prevent an otherwise authorized backend operation.
            overlay.hideForOperation(call())?.close() ?: error("No-overlay operation was refused")
        } finally {
            overlay.close()
            shell("cmd appops set ${app.packageName} SYSTEM_ALERT_WINDOW $originalMode")
            if (original == "null" || original.isBlank()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $original")
            }
        }
    }

    @Test fun conversationVisibilityHidesAndRestoresWithoutStopping() {
        withOverlay { overlay, stopped ->
            overlay.bind(call())
            waitUntil { hasControl() }
            conversationVisible.set(true)
            overlay.refreshSurface()
            waitUntil { !hasControl() }
            assertNull(stopped.get())
            assertTrue(overlay.executionAllowed())
            conversationVisible.set(false)
            overlay.refreshSurface()
            waitUntil { hasControl() }
            assertNull(stopped.get())
        }
    }

    private fun withOverlay(block: (MobileUseOverlay, AtomicReference<PluginTaskIdentity?>) -> Unit) {
        val component = ComponentName(context, HelixAccessibilityService::class.java).flattenToString()
        val original = shell("settings get secure enabled_accessibility_services").trim()
        val enabled = original.takeUnless { it == "null" || it.isBlank() }.orEmpty()
        val overlay = AtomicReference<MobileUseOverlay?>()
        val stopped = AtomicReference<PluginTaskIdentity?>()
        val presentation =
            MobileUseOverlay(
                context,
                object : PluginTaskHost {
                    override val conversationVisible: Boolean get() =
                        this@MobileUseOverlayDeviceTest
                            .conversationVisible
                            .get()

                    override fun reviewApproval(
                        task: PluginTaskIdentity,
                        approvalId: String,
                        approve: Boolean,
                    ) = Unit

                    override fun snapshot(task: PluginTaskIdentity) =
                        PluginTaskSnapshot(
                            TurnState.WAITING_MODEL,
                            awaitingDataDisclosure = imageDisclosurePending,
                        )

                    override fun requestStop(task: PluginTaskIdentity) {
                        stopped.set(task)
                    }

                    override fun openConversation(task: PluginTaskIdentity) {
                        returned.set(task)
                    }
                },
                surface = {
                    com.helix.extensions.mobileuse.automation.AutomationServiceController
                        .overlayContext()
                },
            ) { true }.also { overlay.set(it) }
        val resource =
            com.helix.extensions.plugin.NativeResource<
                com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation,
            >(presentation) { it.close() }
        val registration = AutomationRuntimePresentationFactory.register(resource)
        activeRegistration = registration
        try {
            context.startActivity(
                Intent(context, OverlayFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val components = listOf(enabled, component).filter { it.isNotEmpty() }.joinToString(":")
            shell("settings put secure enabled_accessibility_services $components")
            shell("settings put secure accessibility_enabled 1")
            waitUntil {
                com.helix.extensions.mobileuse.automation.AutomationServiceController
                    .overlayContext() != null
            }
            instrumentation.waitForIdleSync()
            block(requireNotNull(overlay.get()), stopped)
        } finally {
            AutomationServiceController.stop(AutomationStopReason.USER_STOP)
            registration.close()
            activeRegistration = null
            if (enabled.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $enabled")
            }
            shell("settings put secure accessibility_enabled ${if (enabled.isEmpty()) 0 else 1}")
            instrumentation.waitForIdleSync()
        }
    }

    private fun call() =
        ExecutableToolCall(
            "overlay-fixture-call",
            "ui.observe",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
            identity.conversationId,
            identity.turnId,
        )

    private fun hasControl(): Boolean = controlBounds() != null

    private fun controlBounds(label: Int = R.string.mobile_overlay_take_over): Rect? {
        for (window in automation.windows) {
            val bounds = window.root?.let { controlBounds(it, label) }
            if (bounds != null) return bounds
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun controlBounds(
        root: android.view.accessibility.AccessibilityNodeInfo,
        label: Int,
    ): Rect? =
        try {
            val nodes = root.findAccessibilityNodeInfosByText(context.getString(label))
            try {
                nodes.firstOrNull { it.isVisibleToUser }?.let { node -> Rect().also(node::getBoundsInScreen) }
            } finally {
                nodes.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }

    private fun hasBlendedPixel(
        before: Bitmap,
        after: Bitmap,
    ): Boolean {
        for (y in dp(50) until dp(130) step 3) {
            for (x in dp(12) until dp(150) step 3) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                if (Color.red(a) == 240 && Color.red(b) in 80..220 && Color.green(b) < Color.green(a)) return true
            }
        }
        return false
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun awaitScreenshot(predicate: (Bitmap) -> Boolean): Bitmap {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val screenshot = requireNotNull(automation.takeScreenshot())
            if (predicate(screenshot)) return screenshot
            screenshot.recycle()
            Thread.sleep(50)
        }
        error("Expected fixture pixels did not reach the compositor")
    }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("Device condition timed out", condition())
    }
}
