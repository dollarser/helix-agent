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
import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentationFactory
import com.helix.extensions.mobileuse.automation.HelixAccessibilityService
import com.helix.extensions.plugin.PluginTaskHost
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
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
                val clicks = OverlayFixtureActivity.clicks.get()
                shell("input tap ${dp(40)} ${dp(90)}")
                waitUntil { OverlayFixtureActivity.clicks.get() > clicks }
                val lease = overlay.hideForOperation(call())
                assertNotNull("Overlay must hide before capture", lease)
                try {
                    assertFalse(hasControl())
                    val hidden = requireNotNull(automation.takeScreenshot())
                    try {
                        assertFalse("Hidden status must not contaminate pixels", hasBlendedPixel(baseline, hidden))
                    } finally {
                        hidden.recycle()
                    }
                } finally {
                    lease?.close()
                }
                waitUntil { hasControl() }
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
                overlay.stopBoundTask()
                assertEquals(identity, stopped.get())
                assertFalse(overlay.executionAllowed())
                assertFalse(overlay.bind(call()))
            } finally {
                lease.close()
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

    @Test fun returningStopsOriginalTaskAndServiceCloseRemovesControls() {
        withOverlay { overlay, stopped ->
            overlay.bind(call())
            waitUntil { controlBounds(R.string.mobile_overlay_return) != null }
            val bounds = requireNotNull(controlBounds(R.string.mobile_overlay_return))
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            waitUntil { returned.get() != null }
            assertEquals(identity, returned.get())
            assertEquals(identity, stopped.get())
            overlay.close()
            waitUntil { !hasControl() }
            assertFalse(overlay.bind(call()))
        }
    }

    private fun withOverlay(block: (MobileUseOverlay, AtomicReference<PluginTaskIdentity?>) -> Unit) {
        val component = ComponentName(context, HelixAccessibilityService::class.java).flattenToString()
        val original = shell("settings get secure enabled_accessibility_services").trim()
        val enabled = original.takeUnless { it == "null" || it.isBlank() }.orEmpty()
        val overlay = AtomicReference<MobileUseOverlay?>()
        val stopped = AtomicReference<PluginTaskIdentity?>()
        val oldFactory = AutomationRuntimePresentationFactory.create
        AutomationRuntimePresentationFactory.create = { service ->
            MobileUseOverlay(
                service,
                object : PluginTaskHost {
                    override fun snapshot(task: PluginTaskIdentity) = PluginTaskSnapshot(TurnState.WAITING_MODEL)

                    override fun requestStop(task: PluginTaskIdentity) {
                        stopped.set(task)
                    }

                    override fun openConversation(task: PluginTaskIdentity) {
                        returned.set(task)
                    }
                },
            ) { true }.also { overlay.set(it) }
        }
        try {
            context.startActivity(
                Intent(context, OverlayFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val components = listOf(enabled, component).filter { it.isNotEmpty() }.joinToString(":")
            shell("settings put secure enabled_accessibility_services $components")
            shell("settings put secure accessibility_enabled 1")
            waitUntil { overlay.get() != null }
            instrumentation.waitForIdleSync()
            block(requireNotNull(overlay.get()), stopped)
        } finally {
            overlay.get()?.close()
            if (enabled.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $enabled")
            }
            shell("settings put secure accessibility_enabled ${if (enabled.isEmpty()) 0 else 1}")
            AutomationRuntimePresentationFactory.create = oldFactory
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
