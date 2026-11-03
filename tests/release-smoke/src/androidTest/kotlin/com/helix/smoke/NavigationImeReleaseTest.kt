package com.helix.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real system IME and public UI only; draft text must survive navigation without a model request. */
class NavigationImeReleaseTest {
    @Test fun openingNavigationHidesTheImeAndPreservesDraftText() {
        val ui = ReleaseUiTestSupport()
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        val marker = "SYNTHETIC_IME_DRAFT_KEEP"
        ui.restartTarget()
        ui.activate("新建会话", "New conversation")
        ui.enterText(marker, focus = true)
        await { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        ui.activate("应用导航", "App navigation")
        await { automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        ui.scrollTo("工具", "Tools")
        ui.activate("工具", "Tools")
        ui.scrollTo("浏览器", "Browser")
        ui.activate("浏览器", "Browser")
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
        await { ui.nodes().any { it.isEditable && it.text?.toString() == marker } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(condition())
    }
}
