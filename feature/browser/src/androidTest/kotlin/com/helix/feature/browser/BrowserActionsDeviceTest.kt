package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserToolBridge
import com.helix.tools.browser.ScreenshotStatus
import com.helix.tools.browser.ScrollStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The on-device verification gate for HXA-062 (verification-matrix row
 * `:feature:browser:connectedDebugAndroidTest`). Runs the REAL System WebView through the
 * production [BrowserToolBridgeImpl] and proves the `browser.*` actions behave fail-closed on a
 * live page, per doc 09 §3.3/§3.4:
 *
 * - a password field and a payment field are REFUSED (the JS gate and the host
 *   [com.helix.tools.browser.SensitiveFieldClassifier] agree; the reason is the category), while
 *   an ordinary field / button PERFORMS;
 * - `browser.type` actually mutates the DOM (a fresh snapshot reads the new value back);
 * - a node token from a pre-navigation snapshot is STALE on the action path after a navigation;
 * - an action addressed to an unknown tab fails closed as NO_TAB;
 * - `browser.scroll` reports SCROLLED on a settled page;
 * - `browser.screenshot` fails closed as NO_PAGE when the tab's WebView is not attached to a
 *   sized window (this harness keeps it detached, as [BrowserSnapshotDeviceTest] does) — the
 *   happy SAVED path is pinned by the JVM mapping test plus core:workspace's writeArtifact tests.
 *
 * Threading: [BrowserController] is main-thread-only, and the bridge encapsulates the main-thread
 * hop internally (its `onMain`/`onMainAsync` post to the main looper and block). So every bridge
 * call is made directly from the (non-main) instrumentation thread, exactly as a real tool
 * executor on Dispatchers.IO would call it.
 */
@RunWith(AndroidJUnit4::class)
class BrowserActionsDeviceTest {
    private lateinit var controller: BrowserController
    private lateinit var bridge: BrowserToolBridge

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        var created: BrowserController? = null
        onMain { created = BrowserController(context) }
        controller = created!!
        // A real store over a temp dir so the bridge is fully constructed. The NO_PAGE screenshot
        // path never writes to it; the SAVED path is exercised by the JVM mapping test instead.
        val screenshotDir = File(context.cacheDir, "hxa062-browser-screenshot").apply { mkdirs() }
        bridge =
            BrowserToolBridgeImpl(
                controller,
                WorkspaceArtifactStore(ScopeRootResolver { screenshotDir.toPath() }),
                "hxa062test",
            )
    }

    @After
    fun tearDown() {
        onMain { controller.destroy() }
    }

    // ── the dual sensitive-field refusal (doc 09 §3.3/§3.4) ──────────────────────────────

    @Test
    fun sensitiveFieldsAreRefusedAndOrdinaryFieldsPerform() {
        val page =
            "data:text/html,<h1>Form</h1><button id=\"go\">Go</button>" +
                "<input type=\"text\" id=\"u\" value=\"user\">" +
                "<input type=\"password\" id=\"pw\" value=\"secret\">" +
                "<input type=\"text\" id=\"cc\" autocomplete=\"cc-number\" value=\"ccv\">"
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, page)

        val snap = bridge.snapshot(tabId)
        assertTrue("a settled page must snapshot", snap.ok)

        // A password field's value is never read, so it is the only field with an empty value.
        val button = snap.nodes.single { it.role == "button" }
        val password = snap.nodes.first { it.role == "field" && it.value.isEmpty() }
        val normal = snap.nodes.first { it.role == "field" && it.value == "user" }
        val payment = snap.nodes.first { it.role == "field" && it.value == "ccv" }

        // A password field is refused, category "password" — never PERFORMED.
        val pw = bridge.click(tabId, password.token)
        assertEquals(ActionStatus.REFUSED, pw.status)
        assertEquals("password", pw.reason)

        // A payment field (cc-number) is refused, category "payment" — the card number is not typed.
        val pay = bridge.type(tabId, payment.token, "4111 1111 1111 1111")
        assertEquals(ActionStatus.REFUSED, pay.status)
        assertEquals("payment", pay.reason)

        // An ordinary button performs; an ordinary field performs.
        assertEquals(ActionStatus.PERFORMED, bridge.click(tabId, button.token).status)
        assertEquals(ActionStatus.PERFORMED, bridge.type(tabId, normal.token, "typed").status)
    }

    @Test
    fun aTypedValueReachesTheFieldInTheRealWebView() {
        val page = "data:text/html,<input type=\"text\" id=\"u\" value=\"before\">"
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, page)

        val token =
            bridge
                .snapshot(tabId)
                .nodes
                .single { it.role == "field" }
                .token
        assertEquals(ActionStatus.PERFORMED, bridge.type(tabId, token, "hello").status)

        // The mutation is real: a fresh snapshot reads the new value back from the DOM.
        val after = bridge.snapshot(tabId)
        assertTrue("the typed value must be present in the DOM", after.ok && after.nodes.any { it.value == "hello" })
    }

    // ── token invalidation on the action path ────────────────────────────────────────────

    @Test
    fun anOldTokenIsStaleOnTheActionPathAfterNavigation() {
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<button id=\"a\">A</button>")
        val snap = bridge.snapshot(tabId)
        assertTrue(snap.ok)
        val token = snap.nodes.single { it.role == "button" }.token

        // A newer committed navigation bumps the generation: the old token is stale.
        navigateToLoadedPage(tabId, "data:text/html,<button id=\"b\">B</button>")
        val outcome = bridge.click(tabId, token)
        assertEquals(ActionStatus.STALE_TOKEN, outcome.status)
        assertEquals("stale-generation", outcome.reason)
    }

    @Test
    fun anActionOnAnUnknownTabFailsClosedAsNoTab() {
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<button id=\"a\">A</button>")
        val token =
            bridge
                .snapshot(tabId)
                .nodes
                .single { it.role == "button" }
                .token

        // A well-formed token from a real tab, but addressed to a tab that does not exist.
        assertEquals(ActionStatus.NO_TAB, bridge.click("no-such-tab", token).status)
    }

    // ── scroll / screenshot ──────────────────────────────────────────────────────────────

    @Test
    fun scrollOnASettledPageReportsScrolled() {
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<h1>S</h1>")
        assertEquals(ScrollStatus.SCROLLED, bridge.scroll(tabId, 0, 200).status)
    }

    @Test
    fun screenshotOfAnUnattachedWebViewFailsClosedAsNoPage() {
        // This harness keeps the WebView detached (no window, no size), so there is no renderable
        // page: the bridge must fail closed as NO_PAGE, never crash or emit a bogus capture.
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<h1>S</h1>")
        assertEquals(ScreenshotStatus.NO_PAGE, bridge.screenshot(tabId).status)
    }

    // ── open loads a real tab ─────────────────────────────────────────────────────────────

    @Test
    fun openLoadsANewTabThatSnapshots() {
        val opened = bridge.open("data:text/html,<h1>Opened</h1>")
        assertEquals("data:opaque", opened.origin)
        assertTrue("open must return a usable tab id", opened.tabId.isNotBlank())
        settleLoaded(opened.tabId)

        val snap = bridge.snapshot(opened.tabId)
        assertTrue("the opened tab must snapshot", snap.ok)
        assertTrue(snap.nodes.any { it.role == "heading" && it.text == "Opened" })
    }

    // ---------------------------------------------------------------- helpers

    /** Navigates [tabId] to [dataUrl] (a `data:` document) and waits for the committed page. */
    private fun navigateToLoadedPage(
        tabId: String,
        dataUrl: String,
    ) {
        onMain { controller.navigate(tabId, dataUrl) }
        settleLoaded(tabId)
    }

    /** Polls the thread-safe StateFlow until [tabId]'s page is committed (or times out). */
    private fun settleLoaded(tabId: String) {
        awaitState("page $tabId finished") {
            controller.state.value.tabs
                .firstOrNull { it.id == tabId }
                ?.takeIf { !it.isLoading && it.error == null && it.navigationGeneration >= 1 }
        }
    }

    /** Runs [controller] work on the app main thread and returns its value. */
    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val holder = arrayOfNulls<Any>(1)
        val failure = arrayOfNulls<Throwable>(1)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                holder[0] = action()
            } catch (t: Throwable) {
                failure[0] = t
            } finally {
                latch.countDown()
            }
        }
        assertTrue("main-thread work timed out", latch.await(30, TimeUnit.SECONDS))
        failure[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    /** Polls [condition] (against the thread-safe StateFlow) until non-null or timeout. */
    private fun <T> awaitState(
        description: String,
        condition: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + DEFAULT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val value = condition()
            if (value != null) return value
            Thread.sleep(100L)
        }
        org.junit.Assert.fail("timed out: $description")
        error("unreachable")
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 15_000L
    }
}
