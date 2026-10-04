package com.helix.app.eval

import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.tools.automation.AutomationActionStatus
import com.helix.tools.automation.AutomationNodeAction
import com.helix.tools.automation.AutomationNodeActionRequest
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Opt-in owned-emulator regression for PackageInstaller Accessibility visibility.
 *
 * Precondition: the current foreground surface is an APK confirmation dialog whose Install button
 * is visible. The test deliberately does not launch an Activity or use a model; it validates the
 * production Mobile Use snapshot/action path directly.
 */
@RunWith(AndroidJUnit4::class)
class MobileUsePackageInstallerVisibilityDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Suppress("unused")
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val center get() = AutomationPermissionCenter(app)
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Suppress("LongMethod")
    @Test
    fun currentPackageInstallerExposesAndClicksInstall() {
        val enabled = arguments.getString(ARG_ENABLED)
        assumeTrue(enabled != null)
        require(enabled == "true") { "$ARG_ENABLED must be true" }
        val previousUpdate = packageUpdateTime()

        val previousServices =
            android.provider.Settings.Secure.getString(
                app.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        val accessibilityEnabled =
            android.provider.Settings.Secure.getInt(
                app.contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            )
        val conversation = "package-installer-visibility-${System.nanoTime()}"
        container.storage.sessions.create(
            conversation,
            "PackageInstaller visibility fixture",
            null,
            null,
            System.currentTimeMillis(),
        )
        try {
            ensureAccessibility(previousServices)
            center.authorizeConversation(conversation, emptySet(), wholePhone = true)
            val port = evaluationAutomationPort(center, conversation)
            var snapshot = port.snapshot().snapshot
            waitUntil(failureMessage = { "latest snapshot=$snapshot" }) {
                snapshot = port.snapshot().snapshot
                snapshot
                    ?.nodes
                    ?.any { node ->
                        node.enabled &&
                            node.clickable &&
                            node.viewId == "android:id/button1"
                    } == true
            }

            val captured = requireNotNull(snapshot)
            assertEquals("com.google.android.packageinstaller", captured.packageName)
            println("ACCESSIBILITY_CONFIRMATION=$captured")
            val install =
                captured.nodes.single { node ->
                    node.enabled &&
                        node.clickable &&
                        node.viewId == "android:id/button1"
                }
            val action =
                port.nodeAction(
                    AutomationNodeActionRequest(
                        action = AutomationNodeAction.CLICK,
                        token = install.token,
                    ),
                )
            assertEquals(AutomationActionStatus.SUCCEEDED, action.status)

            waitUntil(timeoutMillis = 60_000, failureMessage = { "No new WeChat package update" }) {
                packageUpdateTime() > previousUpdate
            }
            println("ACCESSIBILITY_PACKAGE_UPDATE=$previousUpdate->${packageUpdateTime()}")
        } finally {
            center.revokeConversation(conversation)
            runCatching { container.storage.sessions.archive(conversation, System.currentTimeMillis()) }
            restoreAccessibility(previousServices, accessibilityEnabled)
        }
    }

    private fun ensureAccessibility(previousServices: String?) {
        val component = "${app.packageName}/com.helix.tools.automation.HelixAccessibilityService"
        val previous = previousServices.orEmpty().split(':').filter(String::isNotBlank)
        val withoutHelix = previous.filterNot { it == component }
        if (withoutHelix.isEmpty()) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
        } else {
            shell("settings put secure enabled_accessibility_services ${withoutHelix.joinToString(":")}")
        }
        SystemClock.sleep(500)
        val allServices = (withoutHelix + component).distinct()
        shell("settings put secure enabled_accessibility_services ${allServices.joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        waitUntil(timeoutMillis = 20_000) { center.serviceState() == AutomationServiceState.CONNECTED }
    }

    private fun restoreAccessibility(
        previousServices: String?,
        accessibilityEnabled: Int,
    ) {
        val previous = previousServices.orEmpty()
        if (previous.isBlank()) {
            shell("settings delete secure enabled_accessibility_services")
        } else {
            shell("settings put secure enabled_accessibility_services $previous")
        }
        shell("settings put secure accessibility_enabled $accessibilityEnabled")
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }.also {
            descriptor.close()
        }
    }

    private fun packageUpdateTime(): Long =
        try {
            app.packageManager.getPackageInfo(WECHAT_PACKAGE, 0).lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }

    private fun waitUntil(
        timeoutMillis: Long = 10_000,
        failureMessage: () -> String = { "condition was not met" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            satisfied = condition()
        }
        assertTrue("${failureMessage()} within ${timeoutMillis}ms", satisfied)
    }

    private companion object {
        const val ARG_ENABLED = "helixPackageInstallerVisibility"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
