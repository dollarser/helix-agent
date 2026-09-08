package com.helix.feature.browser

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserOwnerDeviceTest {
    @Test
    fun noOwnerDoesNotPretendToOpenOrNavigate() {
        onMain {
            val controller = BrowserController(instrumentation.targetContext)
            assertEquals("browser-host-unavailable", controller.openTab("https://example.com").failureReason)
            assertTrue(
                controller.state.value.tabs
                    .isEmpty(),
            )
            val id = controller.newTab()
            assertEquals(
                BrowserNavResult.Denied("browser-host-unavailable"),
                controller.navigateOutcome(id, "https://example.com"),
            )
            assertFalse(controller.tab(id)!!.isLoading)
            assertNull(controller.hostView(id))
        }
    }

    @Test
    fun oldDetachAndLatePageCallbackCannotAffectReplacement() {
        BrowserActivityFixture().use { fixture ->
            val controller = fixture.controller
            val id = load(fixture)
            fixture.scenario.onActivity { activity ->
                val oldView = controller.hostView(id)!!
                val oldClient = oldView.webViewClient
                val oldOwner = activity.owner
                val newOwner = BrowserViewOwner(activity)
                controller.attach(newOwner)
                activity.owner = newOwner
                controller.resume(newOwner)
                controller.detach(oldOwner)
                assertFalse(oldOwner.available)
                assertNull(controller.hostView(id))
                assertFalse(controller.tab(id)!!.isLoading)
                assertFalse(controller.tab(id)!!.canGoBack)
                val state = controller.state.value
                oldClient.onPageFinished(oldView, "https://stale.example/")
                assertEquals(state, controller.state.value)
                controller.navigate(id, PAGE)
                val replacement = controller.hostView(id)!!
                assertSame(activity, replacement.context)
                assertNotSame(oldView, replacement)
                controller.pause(oldOwner)
                assertTrue(newOwner.resumed)
                assertTrue(controller.reloadOutcome(id) is BrowserReloadResult.NoChange)
            }
        }
    }

    @Test
    fun backgroundKeepsTheSameActivityViewAndTokensAreRetiredOnDetach() {
        BrowserActivityFixture().use { fixture ->
            val id = load(fixture)
            val controller = fixture.controller
            val view = onMain { controller.hostView(id)!! }
            fixture.scenario.moveToState(Lifecycle.State.CREATED)
            assertSame(view, onMain { controller.hostView(id) })
            fixture.scenario.moveToState(Lifecycle.State.RESUMED)
            assertSame(view, onMain { controller.hostView(id) })
            fixture.scenario.onActivity { activity ->
                val generation = controller.tab(id)!!.navigationGeneration
                controller.detach(activity.owner)
                assertNull(controller.hostView(id))
                assertTrue(controller.tab(id)!!.navigationGeneration > generation)
                assertTrue(controller.reloadOutcome(id) is BrowserReloadResult.NoChange)
            }
        }
    }

    @Test
    fun alertConfirmAndPromptUseTheActivityWindow() {
        BrowserActivityFixture().use { fixture ->
            val id = load(fixture)
            val view = onMain { fixture.controller.hostView(id)!! }
            for ((script, expected) in listOf(
                "alert('owner-alert');'ok'" to "\"ok\"",
                "confirm('owner-confirm')" to "true",
                "prompt('owner-prompt','fixture-value')" to "\"fixture-value\"",
            )) {
                val result = arrayOfNulls<String>(1)
                val latch = CountDownLatch(1)
                onMain {
                    view.evaluateJavascript(script) {
                        result[0] = it
                        latch.countDown()
                    }
                }
                clickNode { it.viewIdResourceName == "android:id/button1" }
                assertTrue(latch.await(10, TimeUnit.SECONDS))
                assertEquals(expected, result[0])
            }
        }
    }

    @Test
    fun backgroundAndSelectionEndPendingDialogResults() {
        BrowserActivityFixture().use { fixture ->
            val id = load(fixture)
            val view = onMain { fixture.controller.hostView(id)!! }
            val answer = arrayOfNulls<String>(1)
            val latch = CountDownLatch(1)
            onMain {
                view.evaluateJavascript("confirm('owner-cancel')") {
                    answer[0] = it
                    latch.countDown()
                }
            }
            waitNode { it.text?.toString() == "owner-cancel" }
            fixture.scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals("false", answer[0])
            fixture.scenario.moveToState(Lifecycle.State.RESUMED)
            onMain { fixture.controller.newTab() }
            assertEquals("false", evaluate(view, "confirm('inactive-tab')"))
        }
    }

    @Test
    fun stopNavigationAndSelectionCancelAnAlreadyVisibleDialog() {
        BrowserActivityFixture().use { fixture ->
            val id = load(fixture)
            val controller = fixture.controller
            val view = onMain { controller.hostView(id)!! }
            for (cancel in listOf<() -> Unit>({
                controller.stop(id)
            }, { controller.navigate(id, PAGE) }, { controller.newTab() })) {
                val latch = CountDownLatch(1)
                var result: String? = null
                onMain {
                    view.evaluateJavascript("confirm('pending-dialog')") {
                        result = it
                        latch.countDown()
                    }
                }
                waitNode { it.text?.toString() == "pending-dialog" }
                onMain(cancel)
                assertTrue(latch.await(10, TimeUnit.SECONDS))
                assertEquals("false", result)
            }
        }
    }

    private fun load(fixture: BrowserActivityFixture): String {
        val id = onMain { fixture.controller.openTab(PAGE).tabId }
        fixture.scenario.onActivity { it.setContentView(fixture.controller.hostView(id)) }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (fixture.controller.tab(id)?.isLoading != false &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(20)
        }
        assertFalse(fixture.controller.tab(id)!!.isLoading)
        return id
    }

    companion object {
        private const val PAGE = "data:text/html,<html><body><h1>Owner fixture</h1></body></html>"
        val instrumentation get() = InstrumentationRegistry.getInstrumentation()

        fun <T> onMain(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }

        fun evaluate(
            view: WebView,
            script: String,
        ): String? {
            val latch = CountDownLatch(1)
            var value: String? = null
            onMain {
                view.evaluateJavascript(script) {
                    value = it
                    latch.countDown()
                }
            }
            assertTrue("JS result", latch.await(10, TimeUnit.SECONDS))
            return value
        }

        fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val automation = instrumentation.uiAutomation
            automation.serviceInfo =
                automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                val roots = automation.windows.map { it.root } + automation.rootInActiveWindow
                roots.firstNotNullOfOrNull { find(it, predicate) }?.let { return it }
                SystemClock.sleep(40)
            }
            error("Expected window node was not shown")
        }

        fun clickNode(predicate: (AccessibilityNodeInfo) -> Boolean) {
            var node: AccessibilityNodeInfo? = waitNode(predicate)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("click node", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }

        private fun find(
            node: AccessibilityNodeInfo?,
            predicate: (AccessibilityNodeInfo) -> Boolean,
        ): AccessibilityNodeInfo? {
            if (node == null || predicate(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), predicate) }
        }
    }
}
