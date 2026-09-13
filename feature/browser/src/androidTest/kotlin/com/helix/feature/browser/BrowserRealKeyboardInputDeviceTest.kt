package com.helix.feature.browser

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.evaluate
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.instrumentation
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.onMain
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.waitNode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the input capability the activity-owned browser depends on: real keyboard input reaches the
 * WebView's DOM field on this API. [BrowserAutofillDeviceTest] (and the EV-02 autofill soak) drive
 * the field through this path; a failure here means keyboard input into the activity-owned WebView
 * is broken on this API (a product-side input-path issue), independent of the autofill fill/save
 * flow that the autofill test verifies.
 *
 * Forensic origin: on API36 a directly-called `WebView.onCreateInputConnection(EditorInfo())`
 * returns null under an accessibility-based focus even though real key events still deliver
 * characters to the DOM — so "input works" had to be proven by actually delivering keys. Both the
 * JS-focused and accessibility-focused states are asserted.
 */
@RunWith(AndroidJUnit4::class)
class BrowserRealKeyboardInputDeviceTest {
    private val page =
        "data:text/html,<html><body>" +
            "<input id='username' type='text' autocomplete='username' " +
            "style='margin:40px;width:300px;height:80px;font-size:28px'>" +
            "</body></html>"

    @Test
    fun realKeyEventsReachTheActivityOwnedWebView() {
        val api = Build.VERSION.SDK_INT
        Log.i("IME_DIAG", "BrowserRealKeyboardInput API$api")
        BrowserActivityFixture().use { fixture ->
            val controller = fixture.controller
            val id = onMain { controller.openTab(page).tabId }
            fixture.scenario.onActivity { it.setContentView(controller.hostView(id)) }
            waitLoaded(controller, id)
            val view = onMain { controller.hostView(id)!! }
            instrumentation.waitForIdleSync()

            // JS focus + real key events -> the characters land in the DOM.
            onMain { view.requestFocus() }
            evaluate(view, "document.getElementById('username').focus(); 'focused'")
            SystemClock.sleep(300)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
            SystemClock.sleep(500)
            assertEquals(
                "real keys after JS focus (API$api)",
                "\"ab\"",
                evaluate(view, "document.getElementById('username').value"),
            )

            // Accessibility focus (the autofill path's mechanism) + real key events -> land too.
            evaluate(view, "document.getElementById('username').value=''; 'reset'")
            focusAccessibility(view)
            SystemClock.sleep(300)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_C)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_D)
            SystemClock.sleep(500)
            assertEquals(
                "real keys after accessibility focus (API$api)",
                "\"cd\"",
                evaluate(view, "document.getElementById('username').value"),
            )
        }
    }

    private fun waitLoaded(
        controller: BrowserController,
        id: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while ((controller.tab(id)?.isLoading != false) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
        }
    }

    private fun focusAccessibility(view: android.webkit.WebView) {
        onMain { view.requestFocus() }
        val node =
            waitNode {
                it.className?.toString() == "android.widget.EditText" && !it.isPassword &&
                    it.packageName?.toString() == instrumentation.targetContext.packageName
            }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
    }
}
