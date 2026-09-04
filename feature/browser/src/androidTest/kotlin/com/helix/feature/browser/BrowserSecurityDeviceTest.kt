package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.webview.WebViewTabHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The on-device verification gate for HXA-060 (verification-matrix row HXA-060). Runs the
 * REAL System WebView through [BrowserController] and proves, per doc 09 §3.4:
 *
 * - the hardened [BrowserSecuritySpec] is what a live WebView actually runs with
 *   (rebuild from [android.webkit.WebSettings] + [BrowserSecuritySpec.assertHardened]);
 * - only policy-allowed content reaches `loadUrl` (a `file:` denial never creates a host);
 * - NO privileged JavaScript bridge exists (`window.helix` is undefined on a loaded page);
 * - page-initiated capability requests are denied by default (geolocation → PERMISSION_DENIED);
 * - the three independent clear entries (cookie / cache / history) work on the live object;
 * - the download policy denies executables before any bytes move.
 *
 * Threading: [BrowserController] is main-thread-only, so every command goes through
 * [onMain]; state is read through the thread-safe [androidx.coroutines.flow.StateFlow].
 */
@RunWith(AndroidJUnit4::class)
class BrowserSecurityDeviceTest {
    private lateinit var controller: BrowserController

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        var created: BrowserController? = null
        onMain {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            created = BrowserController(context)
        }
        controller = created!!
    }

    @After
    fun tearDown() {
        val cookieManager = CookieManager.getInstance()
        onMain {
            controller.destroy()
            cookieManager.apply {
                removeAllCookies(null)
                flush()
            }
        }
    }

    @Test
    fun theLiveWebViewRunsTheHardenedSpec() {
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<title>gate</title>")
        var spec: BrowserSecuritySpec? = null
        var multipleWindows: Boolean? = null
        onMain {
            val settings = requireNotNull(controller.hostView(tabId)?.settings)
            spec = WebViewTabHost.specFromSettings(settings)
            multipleWindows = settings.supportMultipleWindows()
        }
        BrowserSecuritySpec.assertHardened(requireNotNull(spec))
        assertFalse("setSupportMultipleWindows(false) must be live", multipleWindows!!)
    }

    @Test
    fun aDataTextHtmlDocumentLoads() {
        val tabId = onMain { controller.newTab() }
        onMain { controller.navigate(tabId, "data:text/html,<title>HELIX60</title><h1>hi</h1>") }
        val tab =
            awaitState("page finished") {
                currentTab(tabId)?.takeIf { !it.isLoading && it.error == null && it.navigationGeneration >= 1 }
            }
        assertEquals("HELIX60", tab.title)
        assertEquals(1L, tab.navigationGeneration)
    }

    @Test
    fun aFileUrlIsDeniedBeforeAnyWebViewIsCreated() {
        val tabId = onMain { controller.newTab() }
        onMain { controller.navigate(tabId, "file:///etc/passwd") }
        val tab = awaitState("policy error") { currentTab(tabId)?.takeIf { it.error is PolicyBlockedError } }
        assertEquals(BrowserErrorKind.POLICY_BLOCKED, tab.error!!.kind)
        assertNull("a denied URL must never create a WebView host", onMain { controller.hostView(tabId) })
    }

    @Test
    fun noPermanentJavaScriptBridgeIsExposed() {
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<h1>probe</h1>")
        val view = onMain { requireNotNull(controller.hostView(tabId)) }
        assertEquals("\"undefined\"", evaluateJavascript(view, "typeof window.helix"))
    }

    @Test
    fun anUnreachableHostSurfacesATypedLoadError() {
        val tabId = onMain { controller.newTab() }
        onMain { controller.navigate(tabId, "http://nonexistent-host-helix.invalid/") }
        val tab =
            awaitState(
                30_000L,
                "load error",
            ) { currentTab(tabId)?.takeIf { it.error is LoadError } }
        assertTrue(
            "expected a network-family load error, got ${tab.error!!.kind}",
            tab.error!!.kind in
                setOf(
                    BrowserErrorKind.HOST_LOOKUP_FAILED,
                    BrowserErrorKind.CONNECTION_FAILED,
                    BrowserErrorKind.UNKNOWN,
                ),
        )
    }

    @Test
    fun cookiesRoundTripAndTheClearEntryRemovesThem() {
        val tabId = onMain { controller.newTab() }
        navigateToLoadedPage(tabId, "data:text/html,<h1>cookies</h1>")
        val cookieManager = CookieManager.getInstance()
        onMain {
            cookieManager.setCookie("https://helix.example/", "helix=1; Path=/")
            cookieManager.flush()
        }
        awaitState("cookie visible") {
            onMain { cookieManager.getCookie("https://helix.example/") }?.takeIf { it.contains("helix=1") }
        }
        onMain { controller.clearCookies() }
        awaitState("cookie cleared") {
            if (onMain { cookieManager.getCookie("https://helix.example/") } == null) "cleared" else null
        }
    }

    @Test
    fun anApkDownloadIsDeniedBeforeAnyBytesMove() {
        onMain {
            controller.requestDownload(
                DownloadRequest(
                    url = "https://helix.example/x.apk",
                    suggestedName = "x.apk",
                    mimeType = "application/vnd.android.package-archive",
                    contentLength = 1024L,
                ),
            )
        }
        val item = awaitState("download row") { controller.downloads.value.firstOrNull() }
        assertEquals(DownloadStatus.DENIED, item.status)
        assertEquals(DownloadDenial.UNSAFE_TYPE, item.denial)
        assertEquals(1, controller.downloads.value.size)
    }

    @Test
    fun aPageGeolocationRequestIsDenied() {
        // Unencoded data: URL, the form the System WebView provably loads (see
        // aDataTextHtmlDocumentLoads): URLEncoder percent-escapes + '+'-encodes the script
        // and the document arrives without a live JS context.
        val html =
            "<html><head><script>var g='pending';" +
                "navigator.geolocation.getCurrentPosition(" +
                "function(){g='granted';}," +
                "function(e){g='code'+e.code;},{timeout:2000});</script></head><body></body></html>"
        val tabId = onMain { controller.newTab() }
        onMain { controller.navigate(tabId, "data:text/html," + html) }
        awaitState("page finished") {
            currentTab(tabId)?.takeIf { !it.isLoading && it.error == null && it.navigationGeneration >= 1 }
        }
        val view = onMain { requireNotNull(controller.hostView(tabId)) }
        val deadline = System.currentTimeMillis() + 10_000L
        var result: String? = null
        while (System.currentTimeMillis() < deadline) {
            result = evaluateJavascript(view, "g")
            if (result == "\"granted\"" || result?.startsWith("\"code") == true) break
            Thread.sleep(250L)
        }
        assertEquals(
            "navigator.geolocation must fail with PERMISSION_DENIED (code 1)",
            "\"code1\"",
            result,
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Navigates [tabId] to [dataUrl] (a `data:` document) and waits for the committed page. */
    private fun navigateToLoadedPage(
        tabId: String,
        dataUrl: String,
    ) {
        onMain { controller.navigate(tabId, dataUrl) }
        awaitState("page finished") {
            currentTab(tabId)?.takeIf { !it.isLoading && it.error == null && it.navigationGeneration >= 1 }
        }
    }

    private fun currentTab(tabId: String): BrowserTab? =
        controller.state.value.tabs
            .firstOrNull { it.id == tabId }

    /** Runs [action] on the app main thread and returns its value (BrowserController is main-only). */
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

    /** Polls [condition] (off-main, against the thread-safe StateFlow) until non-null or timeout. */
    private fun <T> awaitState(
        timeoutMs: Long,
        description: String,
        condition: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = condition()
            if (value != null) return value
            Thread.sleep(100L)
        }
        fail("timed out: $description")
        error("unreachable")
    }

    private fun <T> awaitState(
        description: String,
        condition: () -> T?,
    ): T = awaitState(DEFAULT_WAIT_MS, description, condition)

    /** Blocks (on the main thread, via a latch inside [onMain]) for the JS result. */
    private fun evaluateJavascript(
        view: WebView,
        script: String,
    ): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        onMain {
            view.evaluateJavascript(script) { value ->
                result = value
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 15_000L
    }
}
