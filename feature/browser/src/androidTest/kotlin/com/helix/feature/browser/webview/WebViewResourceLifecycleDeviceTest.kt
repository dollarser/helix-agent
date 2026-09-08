package com.helix.feature.browser.webview

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserTabListener
import com.helix.feature.browser.DownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** HXA-103 device gates for WebView callback and file-descriptor lifecycle. */
@RunWith(AndroidJUnit4::class)
class WebViewResourceLifecycleDeviceTest {
    @Test
    fun destroyRemovesPendingEvaluationDeadlinesAndDropsLateResults() {
        var host: WebViewTabHost? = null
        var callbacks = 0
        onMain {
            host = newHost()
            host!!.evaluateFixed("1 + 1") { callbacks += 1 }
            host!!.destroy()
            assertEquals(0, host!!.pendingEvaluationCountForTest())
        }
        val callbacksAtDestroy = callbacks
        Thread.sleep(WebViewTabHost.SNAPSHOT_TIMEOUT_MS + 250L)
        assertEquals("destroyed tabs must not deliver a later JS/deadline callback", callbacksAtDestroy, callbacks)
    }

    @Test
    fun repeatedCreateDestroyDoesNotGrowTargetProcessDescriptors() {
        repeat(WARMUP_CYCLES) { createAndDestroy() }
        settleWebViewCleanup()
        val baseline = descriptorCount()

        repeat(MEASURED_CYCLES) { createAndDestroy() }
        settleWebViewCleanup()
        val after = descriptorCount()

        assertTrue(
            "WebView create/destroy leaked target-process descriptors: baseline=$baseline after=$after",
            after <= baseline + MAX_DESCRIPTOR_DRIFT,
        )
    }

    /** Explicit host-only duration; one process retains the original baseline for the entire soak. */
    @Test
    fun continuousResourceSoak() {
        val seconds = InstrumentationRegistry.getArguments().getString("helix.soak.seconds")?.toLong()
        assumeTrue("requires the HXA-103 host soak runner", seconds != null)
        require(seconds!! in 60L..86_400L)
        // Exercise JS/renderer startup as well as host creation before the fixed baseline.
        repeat(WARMUP_CYCLES) { destroyRemovesPendingEvaluationDeadlinesAndDropsLateResults() }
        repeat(WARMUP_CYCLES) { createAndDestroy() }
        settleWebViewCleanup()
        val baselineFd = descriptorCount()
        val baselineThreads = File("/proc/self/task").list()!!.size
        val baselinePss = Debug.getPss()
        val started = SystemClock.elapsedRealtime()
        var cycles = 0
        do {
            destroyRemovesPendingEvaluationDeadlinesAndDropsLateResults()
            repeatedCreateDestroyDoesNotGrowTargetProcessDescriptors()
            cycles += 1
            val fd = descriptorCount()
            val threads = File("/proc/self/task").list()!!.size
            val pss = Debug.getPss()
            assertTrue("cumulative FD drift: $baselineFd -> $fd", fd <= baselineFd + MAX_DESCRIPTOR_DRIFT)
            assertTrue("cumulative thread drift: $baselineThreads -> $threads", threads <= baselineThreads + 16)
            assertTrue("cumulative PSS drift: $baselinePss -> $pss", pss <= baselinePss + 96 * 1024)
            Log.i(
                "HelixSoak",
                "pid=${Process.myPid()} cycles=$cycles elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "fd=$fd threads=$threads pssKb=$pss",
            )
        } while (SystemClock.elapsedRealtime() - started < seconds * 1_000L)
    }

    /** Independent platform control: no Helix host, clients, callbacks or forced GC. */
    @Test
    fun rawPlatformWebViewLifecycleControl() {
        val count = InstrumentationRegistry.getArguments().getString("helix.rawWebView.count")?.toInt()
        assumeTrue("requires an explicit dedicated-device diagnostic count", count != null)
        require(count!! in 1..40_000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        repeat(count) { index ->
            onMain { android.webkit.WebView(context).destroy() }
            if ((index + 1) % 100 == 0) {
                Log.i("HelixRawWebView", "pid=${Process.myPid()} created=${index + 1}")
                Thread.sleep(100)
            }
        }
    }

    private fun createAndDestroy() = onMain { newHost().destroy() }

    private fun newHost(): WebViewTabHost =
        WebViewTabHost(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            NoOpListener,
        )

    private fun settleWebViewCleanup() {
        Thread.sleep(1_000L)
    }

    private fun descriptorCount(): Int = File("/proc/self/fd").list()?.size ?: error("/proc/self/fd is unavailable")

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        assertTrue("main-thread WebView operation timed out", done.await(20, TimeUnit.SECONDS))
        failure?.let { throw it }
    }

    private object NoOpListener : BrowserTabListener {
        override fun onPageStarted(url: String) = Unit

        override fun onPageFinished(
            url: String,
            title: String?,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ) = Unit

        override fun onMainFrameError(
            netError: Int,
            clientError: Int,
            failingUrl: String?,
        ) = Unit

        override fun onMainFrameUnknownError(failingUrl: String?) = Unit

        override fun onSslError(failingUrl: String) = Unit

        override fun onNavigationAttempt(url: String) = Unit

        override fun onDownloadRequest(request: DownloadRequest) = Unit
    }

    private companion object {
        const val WARMUP_CYCLES = 2
        const val MEASURED_CYCLES = 12
        const val MAX_DESCRIPTOR_DRIFT = 8
    }
}
