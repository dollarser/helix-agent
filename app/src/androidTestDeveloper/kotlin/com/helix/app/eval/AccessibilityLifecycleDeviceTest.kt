package com.helix.app.eval

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationNodeAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.extensions.mobileuse.automation.AutomationSessionStartStatus
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.tools.deviceaccess.DeviceAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration

/** Run with am instrument --no-restart; no model, durable Turn, permission expansion or input effect. */
class AccessibilityLifecycleDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center = AutomationPermissionCenter(app)
    private val component =
        android.content.ComponentName(
            app.packageName,
            "com.helix.extensions.mobileuse.automation.HelixAccessibilityService",
        )

    @Test fun normalDisableAndReenableRetiresRuntimeWithoutChangingConfiguration(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("helix.accessibilityLifecycle") == "true")
            val avd = requireNotNull(args.getString("avdName"))
            require(avd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
            val ui =
                InstrumentationRegistry
                    .getInstrumentation()
                    .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            check(shell(ui, "getprop ro.kernel.qemu").trim() == "1")
            check(shell(ui, "getprop ro.boot.qemu.avd_name").trim() == avd)
            check(
                container.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "BLOCKED: active Turn" }
            check(center.activeSession() == null) { "BLOCKED: active automation runtime" }
            val services = shell(ui, "settings get secure enabled_accessibility_services").trim()
            val enabled = shell(ui, "settings get secure accessibility_enabled").trim()
            check(enabled == "1" && component.flattenToString() in components(services)) {
                "BLOCKED: existing enabled service grant required"
            }
            await("initial binding; start this fixture with --no-restart") {
                center.serviceState() == AutomationServiceState.CONNECTED
            }
            val configuration = configuration()
            check(configuration.lines.isNotEmpty()) { "BLOCKED: explicit existing Mobile Use configuration required" }
            val allowlist = center.allowlistedPackages()
            val target = currentTarget(ui)
            val grant = requireNotNull(configuration.global)
            check(!grant.backgroundDisplay && grant.scope.permitsPackage(target)) {
                "BLOCKED: foreground target outside existing main-display configuration"
            }
            try {
                // Test-only manual runtime selection: one target already admitted by the original global scope.
                // This does not change plugin configuration or grant whole-phone access.
                center.replaceAllowlist(setOf(target))
                val token = observeToken(target, false)
                disableAndVerify(ui, services, token)
                restoreAndVerify(ui, services, enabled, token, target, false)
            } finally {
                // No user Turn or durable session is created. Restoration runs even after an assertion failure.
                center.stopSession()
                center.replaceAllowlist(allowlist)
                writeServices(ui, services)
                shell(ui, "settings put secure accessibility_enabled $enabled")
                await("restored binding") { center.serviceState() == AutomationServiceState.CONNECTED }
                assertEquals(services, shell(ui, "settings get secure enabled_accessibility_services").trim())
                assertEquals(enabled, shell(ui, "settings get secure accessibility_enabled").trim())
                assertEquals(configuration, configuration())
                assertEquals(allowlist, center.allowlistedPackages())
                assertNull(center.activeSession())
                assertTrue(
                    container.storage.turns
                        .listActive()
                        .isEmpty(),
                )
            }
        }

    private fun currentTarget(ui: UiAutomation): String {
        val root = requireNotNull(ui.rootInActiveWindow) { "BLOCKED: no existing foreground window" }
        return try {
            requireNotNull(root.packageName).toString()
        } finally {
            root.recycle()
        }
    }

    private fun observeToken(
        target: String,
        allApplications: Boolean,
    ): String {
        startRuntime(target, allApplications)
        val result = center.snapshot()
        assertEquals(AutomationSnapshotStatus.SUCCESS, result.status)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals(target, snapshot.packageName)
        return snapshot.nodes.first { !it.redacted && it.token.isNotEmpty() }.token
    }

    private fun disableAndVerify(
        ui: UiAutomation,
        original: String,
        token: String,
    ) {
        writeServices(ui, components(original).filter { it != component.flattenToString() }.joinToString(":"))
        await("ordinary system unbind") {
            center.serviceState() == AutomationServiceState.DISABLED &&
                !DeviceAccess.accessibilityConnected(app, component)
        }
        assertNull(center.activeSession())
        assertEquals(AutomationSnapshotStatus.SERVICE_NOT_CONNECTED, center.snapshot().status)
        assertEquals(AutomationActionStatus.SERVICE_NOT_CONNECTED, probeToken(token))
    }

    private fun restoreAndVerify(
        ui: UiAutomation,
        services: String,
        enabled: String,
        token: String,
        target: String,
        allApplications: Boolean,
    ) {
        writeServices(ui, services)
        shell(ui, "settings put secure accessibility_enabled $enabled")
        await("ordinary system rebind") {
            center.serviceState() == AutomationServiceState.CONNECTED &&
                DeviceAccess.accessibilityConnected(app, component)
        }
        // Binding alone must not reactivate the previous runtime or replay its pending action.
        assertNull(center.activeSession())
        assertEquals(AutomationActionStatus.NO_ACTIVE_SESSION, probeToken(token))
        startRuntime(target, allApplications)
        assertTrue(probeToken(token) in setOf(AutomationActionStatus.STALE_TOKEN, AutomationActionStatus.TOKEN_UNKNOWN))
    }

    private fun startRuntime(
        target: String,
        allApplications: Boolean,
    ) {
        val started =
            center.startSession(
                targetPackages = setOf(target),
                ttl = Duration.ofMinutes(1),
                maxActions = 8,
                allowSystemSettings = false,
                allApplications = allApplications,
            )
        assertEquals(AutomationSessionStartStatus.STARTED, started.status)
    }

    private fun probeToken(token: String): AutomationActionStatus =
        center.performNodeAction(AutomationNodeActionRequest(AutomationNodeAction.SET_TEXT, token, text = null)).status
    // Missing text guarantees argument refusal even if a regression accidentally accepts the token;
    // TOKEN_UNKNOWN/STALE_TOKEN assertions still distinguish that regression from valid invalidation.

    private data class Configuration(
        val global: com.helix.extensions.mobileuse.config.MobileUseGrant?,
        val lines: List<String>,
    )

    private fun configuration(): Configuration {
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val store =
            MobileUseGrantStore(lines::lines, lines::setLines, {
                container.pluginService.catalog.selectionId(it, "mobile-use")
            })
        return Configuration(store.globalConfiguration(), lines.lines(MobileUseGrantStore.CONFIG_KEY))
    }

    private fun components(value: String): List<String> =
        value.takeUnless { it == "null" }.orEmpty().split(':').filter(String::isNotEmpty).also { list ->
            require(list.all { it.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) })
        }

    private fun writeServices(
        ui: UiAutomation,
        value: String,
    ) {
        val validated = components(value)
        val command =
            if (validated.isEmpty()) {
                "settings delete secure enabled_accessibility_services"
            } else {
                "settings put secure enabled_accessibility_services ${validated.joinToString(":")}"
            }
        shell(ui, command)
        assertEquals(validated, components(shell(ui, "settings get secure enabled_accessibility_services").trim()))
    }

    private fun await(
        stage: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(condition()) { "BLOCKED: $stage did not settle" }
    }

    private fun shell(
        ui: UiAutomation,
        command: String,
    ): String =
        ui.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes(), Charsets.UTF_8) }
        }
}
