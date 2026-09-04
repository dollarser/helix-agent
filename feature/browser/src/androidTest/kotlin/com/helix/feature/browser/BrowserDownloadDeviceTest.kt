package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.BrowserToolBridge
import com.helix.tools.browser.DownloadToolStatus
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
 * The on-device verification gate for HXA-063 (verification-matrix row
 * `:feature:browser:connectedDebugAndroidTest`).
 *
 * What the device proves here: the PRODUCTION [BrowserToolBridgeImpl.download] entry point, wired
 * against a real on-device [WorkspaceArtifactStore], fail-closes on an untrusted scheme — a
 * non-http URL (and a page-injection-style `javascript:` URL) is REFUSED `url`, and nothing is
 * published. That is the doc 09 §3.4 "downloads must restrict the protocol" guarantee, exercised
 * on real hardware against the real bridge.
 *
 * What is deliberately NOT here, and why: an end-to-end HTTP download (a real `http://127.0.0.1/`
 * SAVED with the exact bytes + SHA-256). An Android app process cannot bind a listening socket —
 * a loopback [java.net.ServerSocket] fails with `EPERM (Operation not permitted)` — so a hermetic
 * in-process HTTP server is impossible on-device (the same reason every other browser device test
 * drives the WebView with `data:` URIs, which the download path does not accept). That full
 * mechanics path (redirect re-validation, the MIME/size/name gates, the capped streaming write)
 * is therefore verified end-to-end on the host JVM by [BrowserDownloaderTest] against a real local
 * server; the device test covers the production entry point's fail-closed behaviour, which is the
 * on-device-specific contract.
 *
 * Threading: `browser.download` does no main-thread hop (pure HTTP + workspace I/O), so it is
 * called directly from the (non-main) instrumentation thread, exactly as a real tool executor would.
 * The [BrowserController] is constructed only because the bridge needs one; the download path never
 * touches the WebView.
 */
@RunWith(AndroidJUnit4::class)
class BrowserDownloadDeviceTest {
    private lateinit var controller: BrowserController
    private lateinit var bridge: BrowserToolBridge
    private lateinit var storeDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        var created: BrowserController? = null
        onMain { created = BrowserController(context) }
        controller = created!!
        storeDir = File(context.cacheDir, "hxa063-download-${System.nanoTime()}").apply { mkdirs() }
        bridge =
            BrowserToolBridgeImpl(
                controller,
                WorkspaceArtifactStore(ScopeRootResolver { storeDir.toPath() }),
                "hxa063test",
            )
    }

    @After
    fun tearDown() {
        onMain { controller.destroy() }
        storeDir.deleteRecursively()
    }

    @Test
    fun aNonHttpUrlIsRefusedWithNothingPublished() {
        val out = bridge.download("file:///etc/passwd", "x")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("url", out.reason)
        assertTrue(
            "nothing may be published on a refused download",
            storeDir
                .walkTopDown()
                .filter { it.isFile }
                .toList()
                .isEmpty(),
        )
    }

    @Test
    fun aJavaScriptSchemeUrlIsRefusedWithNothingPublished() {
        val out = bridge.download("javascript:alert(1)", "x")
        assertEquals(DownloadToolStatus.REFUSED, out.status)
        assertEquals("url", out.reason)
        assertTrue(
            "nothing may be published on a refused download",
            storeDir
                .walkTopDown()
                .filter { it.isFile }
                .toList()
                .isEmpty(),
        )
    }

    // ---------------------------------------------------------------- helpers

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
}
