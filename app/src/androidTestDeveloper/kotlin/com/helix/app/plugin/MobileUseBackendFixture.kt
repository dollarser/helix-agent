package com.helix.app.plugin

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState

/** The host must explicitly enable this test installation's service; the fixture grants nothing. */
internal fun awaitMobileUseTestBackend() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val center = AutomationPermissionCenter(instrumentation.targetContext)
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (center.serviceState() != AutomationServiceState.CONNECTED && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(100)
    }
    check(center.serviceState() == AutomationServiceState.CONNECTED) {
        "Enable the explicitly authorized test installation's accessibility service before this journey"
    }
}
