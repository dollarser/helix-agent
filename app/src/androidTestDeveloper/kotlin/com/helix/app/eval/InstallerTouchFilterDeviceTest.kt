package com.helix.app.eval

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.extensions.mobileuse.automation.PermissionCenterDevicePort
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/** Explicit opt-in: only the disposable Helix Install Fixture, never a third-party package. */
class InstallerTouchFilterDeviceTest {
    @Test
    @Suppress("LongMethod") // Keep the same-dialog input comparison and owned restoration together.
    fun accessibilityAndShellAtIdenticalInstallerCoordinates() =
        runBlocking {
            val enabled = InstrumentationRegistry.getArguments().getString("helixInstallerTouchProbe")
            assumeTrue(enabled != null)
            require(enabled == "true")
            val inst = InstrumentationRegistry.getInstrumentation()
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val backend = OwnedAccessibilityBackend(app)
            val session = "installer-touch-${System.nanoTime()}"
            val center = AutomationPermissionCenter(app)
            c.storage.sessions.create(session, "Installer touch comparison", null, null, System.currentTimeMillis())
            try {
                backend.connect()
                val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                val serviceInfo =
                    app
                        .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                        .installedAccessibilityServiceList
                        .single {
                            it.resolveInfo.serviceInfo.packageName ==
                                app.packageName
                        }
                println("INSTALLER_SERVICE isAccessibilityTool=${serviceInfo.isAccessibilityTool}")
                assertFalse(serviceInfo.isAccessibilityTool)
                assertFalse("Disposable package must initially be absent", installed(automation))
                check(selectMobileUseForTest(session, emptySet(), wholePhone = true))
                app.startActivity(
                    Intent()
                        .setComponent(
                            ComponentName("${app.packageName}.test", AutomationInstallSourceActivity::class.java.name),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var bounds: Rect? = null
                while (bounds == null && SystemClock.elapsedRealtime() < deadline) {
                    val root = automation.rootInActiveWindow
                    val button =
                        root
                            ?.findAccessibilityNodeInfosByViewId("android:id/button1")
                            ?.singleOrNull { it.text?.toString() == "Install" && it.isEnabled }
                    if (button != null && root.packageName?.toString() == "com.google.android.packageinstaller") {
                        bounds = Rect().also(button::getBoundsInScreen)
                        println(
                            "INSTALLER_PRIVILEGED_NODE sensitive=${button.isAccessibilityDataSensitive};bounds=$bounds",
                        )
                        assertTrue("Expected a sensitive installer confirmation", button.isAccessibilityDataSensitive)
                    }
                    if (bounds == null) Thread.sleep(100)
                }
                requireNotNull(bounds) { "No enabled Install confirmation on the owned fixture" }
                val target = stableInstallerBounds(automation)
                val ax = evaluationAutomationPort(center, session).snapshot().snapshot
                checkNotNull(ax)
                assertEquals("com.google.android.packageinstaller", ax.packageName)
                println("INSTALLER_AX_NODE_IDS=${ax.nodes.map { it.viewId }}")
                assertFalse(ax.nodes.any { it.viewId == "android:id/button1" })
                val call =
                    ExecutableToolCall(
                        "installer-touch",
                        "ui.gesture",
                        "1",
                        JsonObject(emptyMap()),
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.now().plusSeconds(90),
                        NoCancellation,
                        sessionId = session,
                        authorizationScopeRef = center.conversationGrant(session)?.scope?.toScopeRef(),
                    )
                val device = PermissionCenterDevicePort(center).forCall(call)
                val (frame, capture) = stableCapture(device, call)
                assertEquals("com.google.android.packageinstaller", frame.target.packageName)
                assertEquals("SAVED", capture.status)
                assertEquals(frame.target.width, capture.width)
                assertEquals(frame.target.height, capture.height)
                app.filesDir.resolve("installer-touch-before.png").writeBytes(requireNotNull(capture.png))
                val x = target.centerX()
                val y = target.centerY()
                println(
                    "INSTALLER_INPUT x=$x;y=$y;width=${capture.width};height=${capture.height};" +
                        "rotation=${frame.target.rotation}",
                )
                val result =
                    device.gesture(
                        frame.token,
                        listOf(AutomationStroke(listOf(AutomationPoint(x.toFloat(), y.toFloat())), 0, 100)),
                        call,
                    )
                println("INSTALLER_AX_GESTURE=$result")
                assertEquals(AutomationActionStatus.SUCCEEDED, result.status)
                Thread.sleep(3000)
                assertFalse("AX touch unexpectedly installed the fixture", installed(automation))
                val stillThere =
                    automation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId("android:id/button1")
                        ?.singleOrNull { it.text?.toString() == "Install" && it.isEnabled }
                val afterBounds = Rect().also { requireNotNull(stillThere).getBoundsInScreen(it) }
                assertEquals("Dialog moved during comparison", target, afterBounds)
                // Shell control only on this synthetic package, not a model/Douyin business success.
                shell(automation, "input tap $x $y")
                val installDeadline = SystemClock.elapsedRealtime() + 60_000
                while (!installed(automation) && SystemClock.elapsedRealtime() < installDeadline) Thread.sleep(200)
                assertTrue("Same-coordinate Shell control failed", installed(automation))
                println("INSTALLER_CONTROL shellUid=${shell(automation, "id -u").trim()};installed=true")
            } finally {
                deselectMobileUseForTest(session)
                backend.close()
                c.storage.deleteSessionPermanently(session)
            }
        }

    @Test
    fun sensitiveAndOrdinaryButtonsKeepTheirOriginalFrameworkAssertions() =
        runBlocking {
            val enabled = InstrumentationRegistry.getArguments().getString("helixInstallerTouchProbe")
            assumeTrue(enabled != null)
            require(enabled == "true")
            require(InstrumentationRegistry.getArguments().getString("helixTouchReview") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            app.appContainer.firstLaunch.markSeen()
            app.startActivity(
                Intent(app, com.helix.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val backend = OwnedAccessibilityBackend(app)
            try {
                backend.connect()
                AccessibilityTouchReviewDeviceTest().fixedCoordinatesSeparateDeliveryFromFrameworkAcceptance()
            } finally {
                backend.close()
            }
        }

    private fun installed(automation: UiAutomation): Boolean =
        shell(automation, "pm path com.helix.validation.installfixture").startsWith("package:")

    private fun stableInstallerBounds(automation: UiAutomation): Rect {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var previous: Rect? = null
        var unchanged = 0
        while (SystemClock.elapsedRealtime() < deadline && unchanged < 10) {
            val button =
                automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("android:id/button1")
                    ?.singleOrNull { it.text?.toString() == "Install" && it.isEnabled }
            val bounds = button?.let { Rect().also(it::getBoundsInScreen) }
            unchanged = if (bounds != null && bounds == previous) unchanged + 1 else 0
            previous = bounds
            Thread.sleep(100)
        }
        check(unchanged == 10) { "Installer bounds did not settle; no gesture attempted" }
        return requireNotNull(previous)
    }

    private fun stableCapture(
        device: com.helix.extensions.mobileuse.automation.AutomationDevicePort,
        call: ExecutableToolCall,
    ): Pair<
        com.helix.extensions.mobileuse.automation.AutomationFrame,
        com.helix.extensions.mobileuse.automation.AutomationScreenshot,
    > {
        repeat(10) {
            Thread.sleep(300)
            val frame = requireNotNull(device.observe().frame)
            val capture = device.screenshot(frame.token, call)
            println("INSTALLER_CAPTURE attempt=$it;status=${capture.status}")
            if (capture.status == "SAVED") return frame to capture
            check(capture.status in setOf("TARGET_OR_AUTHORIZATION_CHANGED", "STALE_TOKEN")) { capture.status }
        }
        error("No stable read-only screenshot; no gesture was attempted")
    }

    private fun shell(
        automation: UiAutomation,
        command: String,
    ): String =
        android.os.ParcelFileDescriptor
            .AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
