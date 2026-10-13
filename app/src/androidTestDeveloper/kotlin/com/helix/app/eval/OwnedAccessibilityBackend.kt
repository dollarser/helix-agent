package com.helix.app.eval

import android.app.UiAutomation
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.coroutines.delay

/** Owned emulator fixture only. Rebinds crashed AX and restores the original settings after execution exits. */
class OwnedAccessibilityBackend(
    private val app: HelixApplication,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var previousServices: String? = null
    private var previousEnabled = 0
    private var backendChanged = false
    private val accessibilityEvents = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun connect() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.setOnAccessibilityEventListener { event ->
            val pkg = event.packageName?.toString()
            if (pkg == app.packageName || pkg == "${app.packageName}.test") {
                accessibilityEvents.merge("$pkg:${event.windowId}:${event.eventType}", 1, Int::plus)
            }
        }
        if (!backendChanged) {
            previousServices = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
            previousEnabled = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
            backendChanged = true
        }
        val component = "${app.packageName}/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
        if (component in previousServices.orEmpty().split(':') &&
            AutomationPermissionCenter(app).serviceState() != AutomationServiceState.CONNECTED
        ) {
            // Instrumentation can force-stop the target while its enabled-service setting remains.
            // Rewriting the same list does not clear the system's crashed-service state.
            val others = previousServices.orEmpty().split(':').filter { it.isNotBlank() && it != component }
            shellOutput(
                automation,
                if (others.isEmpty()) {
                    "settings delete secure enabled_accessibility_services"
                } else {
                    "settings put secure enabled_accessibility_services ${others.joinToString(":")}"
                },
            )
            await {
                val states =
                    shellOutput(automation, "dumpsys accessibility")
                        .lineSequence()
                        .map(String::trim)
                        .filter {
                            it.startsWith("Enabled services:") || it.startsWith("Crashed services:")
                        }.toList()
                states.size >= 2 && states.none { component in it }
            }
            println("MOBILE_BACKEND_REBIND acknowledged=true")
        }
        val services =
            previousServices
                .orEmpty()
                .split(':')
                .filter(String::isNotBlank)
                .toSet() + component
        val command = "settings put secure enabled_accessibility_services ${services.joinToString(":")}"
        shellOutput(automation, command)
        shellOutput(automation, "settings put secure accessibility_enabled 1")
        await { AutomationPermissionCenter(app).serviceState() == AutomationServiceState.CONNECTED }
    }

    private fun shellOutput(
        automation: UiAutomation,
        command: String,
    ): String =
        android.os.ParcelFileDescriptor
            .AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    override fun close() {
        if (!backendChanged) return
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        println("MOBILE_ACCESSIBILITY_EVENTS=$accessibilityEvents")
        automation.setOnAccessibilityEventListener(null)
        automation
            .executeShellCommand(
                if (previousServices.isNullOrBlank()) {
                    "settings delete secure enabled_accessibility_services"
                } else {
                    "settings put secure enabled_accessibility_services $previousServices"
                },
            ).close()
        automation.executeShellCommand("settings put secure accessibility_enabled $previousEnabled").close()
    }

    private suspend fun await(
        timeout: Long = 15000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(200)
        check(condition()) { "Fixture deadline $timeout ms" }
    }
}
