package com.helix.feature.browser

import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fresh-process, local-page comparison. No network, provider/cache reset or speed threshold. */
class BrowserStartupBenchmarkDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    @Suppress("LongMethod") // One sample binds preparation, actual page render and timings to the same owner.
    fun localPageStartupSample() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("browserStartupMode")
        org.junit.Assume.assumeTrue("Requires explicit startup comparison", mode != null)
        require(mode in setOf("cold", "warm"))
        val run = requireNotNull(args.getString("browserStartupRun"))
        require(run.matches(Regex("[a-z0-9-]{1,80}")))
        BrowserActivityFixture().use { fixture ->
            val c = fixture.controller
            assertEquals(
                "Each sample requires a fresh process before any WebView",
                BrowserEngineState.IDLE,
                c.engineState.value,
            )
            val prepareStarted = SystemClock.elapsedRealtimeNanos()
            var prepareMainMs = 0.0
            if (mode == "warm") {
                fixture.scenario.onActivity {
                    val start = SystemClock.elapsedRealtimeNanos()
                    c.warmUpEngine()
                    prepareMainMs = millisSince(start)
                }
                await { c.engineState.value in setOf(BrowserEngineState.READY, BrowserEngineState.FAILED) }
                assertEquals(BrowserEngineState.READY, c.engineState.value)
                assertTrue(
                    c.state.value.tabs
                        .isEmpty(),
                )
            }
            val preparationMs = if (mode == "warm") millisSince(prepareStarted) else 0.0
            var id = ""
            var openMainMs = 0.0
            var view: WebView? = null
            val started = SystemClock.elapsedRealtimeNanos()
            fixture.scenario.onActivity { activity ->
                val start = SystemClock.elapsedRealtimeNanos()
                val opened = c.openTab(PAGE)
                assertEquals(null, opened.failureReason)
                id = opened.tabId
                view = requireNotNull(c.hostView(id))
                activity.setContentView(view, ViewGroup.LayoutParams(-1, -1))
                openMainMs = millisSince(start)
            }
            await { c.tab(id)?.let { !it.isLoading && it.title == "Helix startup probe" } == true }
            val visual = CountDownLatch(1)
            fixture.scenario.onActivity {
                requireNotNull(view).postVisualStateCallback(
                    1,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            visual.countDown()
                        }
                    },
                )
            }
            assertTrue("Page must reach visual readiness", visual.await(10, TimeUnit.SECONDS))
            val readyMs = millisSince(started)
            val dom = CountDownLatch(1)
            var text = ""
            fixture.scenario.onActivity {
                requireNotNull(view).evaluateJavascript("document.getElementById('proof').textContent") {
                    text = it
                    dom.countDown()
                }
            }
            assertTrue(dom.await(5, TimeUnit.SECONDS))
            assertEquals("\"Local page ready\"", text)
            val out = File(instrumentation.targetContext.filesDir, "browser-startup").apply { mkdirs() }
            val image = awaitVisiblePixels()
            val pixelsMs = millisSince(started)
            File(out, "$run.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val record =
                JSONObject()
                    .put("mode", mode)
                    .put("run", run)
                    .put("pid", Process.myPid())
                    .put("preparationMainMs", prepareMainMs)
                    .put("preparationMs", preparationMs)
                    .put("openMainMs", openMainMs)
                    .put("navigationToVisualReadyMs", readyMs)
                    .put("preparationAndNavigationMs", preparationMs + readyMs)
                    .put("navigationToVerifiedPixelsMs", pixelsMs)
                    .put("pixelsVerified", true)
                    .put("domVerified", true)
                    .put("localPage", true)
            File(out, "$run.json").writeText(record.toString())
            println("BROWSER_STARTUP_SAMPLE=$record")
            fixture.scenario.onActivity { c.closeAllTabs() }
        }
    }

    private fun awaitVisiblePixels(): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            instrumentation.waitForIdleSync()
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            if (image.getPixel(image.width / 2, image.height / 2) ==
                android.graphics.Color.rgb(40, 99, 67)
            ) {
                return image
            }
            image.recycle()
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("The local page's expected background never reached the screen")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue("Expected bounded startup condition", condition())
    }

    private fun millisSince(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    private companion object {
        const val PAGE =
            "data:text/html,<html><head><title>Helix startup probe</title>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                "<body style='margin:0;background:rgb(40,99,67);color:white;font:32px sans-serif'>" +
                "<h1 id='proof'>Local page ready</h1></body></html>"
    }
}
