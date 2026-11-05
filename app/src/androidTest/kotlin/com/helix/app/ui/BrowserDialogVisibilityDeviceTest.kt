package com.helix.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BrowserDialogVisibilityDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val visible = mutableStateOf(true)
    private lateinit var controller: BrowserController
    private lateinit var view: WebView
    private lateinit var id: String

    @Test fun delayedDialogsAreCancelledOnNativeHome() = verifyHidden(home = true)

    @Test fun delayedDialogsAreCancelledOutsideBrowser() = verifyHidden(home = false)

    @Test
    fun visibleDialogsWorkAndLeavingSettlesPendingResultsOnce() {
        launchPage()
        for ((script, expected) in listOf(
            "alert('visible-alert');'ok'" to "\"ok\"",
            "confirm('visible-confirm')" to "true",
            "prompt('visible-prompt','fixture')" to "\"fixture\"",
        )) {
            val result = AtomicReference<String?>()
            val done = CountDownLatch(1)
            onMain {
                view.evaluateJavascript(script) {
                    result.set(it)
                    done.countDown()
                }
            }
            waitUntil { node { it.viewIdResourceName == "android:id/button1" } != null }
            assertTrue(
                node { it.viewIdResourceName == "android:id/button1" }!!.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK,
                ),
            )
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(expected, result.get())
        }
        for (home in listOf(false, true)) {
            onMain {
                visible.value = true
                controller.showSelectedPage()
            }
            compose.waitUntil(5_000) { view.isAttachedToWindow }
            val calls = AtomicInteger()
            val result = AtomicReference<String?>()
            val done = CountDownLatch(1)
            onMain {
                view.evaluateJavascript("confirm('pending-visible')") {
                    calls.incrementAndGet()
                    result.set(it)
                    done.countDown()
                }
            }
            waitUntil { node { it.text?.toString() == "pending-visible" } != null }
            hide(home)
            assertTrue("Hidden page must finish its pending JS result", done.await(5, TimeUnit.SECONDS))
            assertEquals("false", result.get())
            assertEquals("\"alive\"", evaluate("'alive'"))
            assertEquals(1, calls.get())
            assertNull(node { it.text?.toString() == "pending-visible" })
            assertSame(view, onMain { controller.hostView(id) })
        }
    }

    private fun verifyHidden(home: Boolean) {
        launchPage()
        try {
            assertEquals(
                "\"scheduled\"",
                evaluate(
                    """
                    window.runDelayedDialogs=false; window.dialogResults=null;
                    function delayedDialog() {
                      if (!window.runDelayedDialogs) { setTimeout(delayedDialog, 20); return; }
                      window.dialogResults=[typeof alert('late-alert'),confirm('late-confirm'),prompt('late-prompt','draft')];
                    }
                    setTimeout(delayedDialog, 20); 'scheduled';
                    """.trimIndent(),
                ),
            )
            hide(home)
            assertSame(view, onMain { controller.hostView(id) })
            assertFalse(onMain { view.isAttachedToWindow })
            assertEquals("true", evaluate("window.runDelayedDialogs=true"))
            val result = AtomicReference<String?>()
            waitUntil {
                onMain { view.evaluateJavascript("JSON.stringify(window.dialogResults)") { result.set(it) } }
                node { it.text?.toString()?.startsWith("late-") == true } != null ||
                    result.get() == "\"[\\\"undefined\\\",false,null]\""
            }
            val popup = node { it.text?.toString()?.startsWith("late-") == true }
            if (popup != null) captureReproduction(home)
            assertNull("Hidden page displayed ${popup?.text}", popup)
            assertEquals("\"[\\\"undefined\\\",false,null]\"", result.get())
        } finally {
            onMain { controller.closeTab(id) }
        }
    }

    private fun captureReproduction(home: Boolean) {
        // Accessibility exposes the window before its entrance animation has painted.
        SystemClock.sleep(250)
        val file = File(instrumentation.targetContext.cacheDir, "browser-dialog-${if (home) "home" else "outside"}.png")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun hide(home: Boolean) {
        onMain { if (home) controller.showHome() else visible.value = false }
        if (home) {
            compose.onNodeWithTag("browser-home-search").assertIsDisplayed()
        } else {
            compose.onNodeWithText("other section").assertIsDisplayed()
        }
        compose.waitUntil(5_000) { !view.isAttachedToWindow }
    }

    private fun launchPage() {
        onMain { controller = BrowserController(browserTestContext()) }
        runBlocking { controller.sessionReady.await() }
        compose.setContent {
            val activity = LocalContext.current as Activity
            DisposableEffect(Unit) {
                val owner = BrowserViewOwner(activity)
                controller.attach(owner)
                controller.resume(owner)
                onDispose { controller.detach(owner) }
            }
            MaterialTheme {
                if (visible.value) BrowserScreen(controller) else Text("other section")
            }
        }
        compose.waitForIdle()
        id =
            onMain {
                controller
                    .openTab(
                        "data:text/html,<title>Dialog fixture</title><body>Visible webpage</body>",
                    ).tabId
            }
        compose.waitUntil(10_000) { controller.tab(id)?.isLoading == false && controller.tab(id)?.title != null }
        view = onMain { controller.hostView(id)!! }
        compose.waitUntil(5_000) { view.isAttachedToWindow && view.isShown }
        instrumentation.uiAutomation.serviceInfo =
            instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
    }

    private fun evaluate(script: String): String? {
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>()
        onMain {
            view.evaluateJavascript(script) {
                result.set(it)
                done.countDown()
            }
        }
        assertTrue("JS callback must complete", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null || predicate(root)) return root
            return (0 until root.childCount).firstNotNullOfOrNull { find(root.getChild(it)) }
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("Expected dialog/result within deadline", predicate())
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
}
