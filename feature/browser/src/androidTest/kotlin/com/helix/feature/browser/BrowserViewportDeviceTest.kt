package com.helix.feature.browser

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose mounting must give percentage-height web apps a definite viewport. */
class BrowserViewportDeviceTest {
    @Test fun percentageHeightContentFillsTheMountedViewport() {
        BrowserActivityFixture().use { fixture ->
            var id = ""
            fixture.scenario.onActivity { activity ->
                id = fixture.controller.tryNewTab()!!
                fixture.controller.navigate(
                    id,
                    "data:text/html,<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<style>html,body,main{height:100%;margin:0}main{background:green}</style>" +
                        "<main>Visible content</main>",
                )
                val view = fixture.controller.hostView(id)!!
                activity.setContentView(
                    ComposeView(activity).apply {
                        setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { view }) }
                    },
                )
            }
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (fixture.controller.tab(id)!!.isLoading && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(30)
            }
            var geometry = ""
            val latch = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = fixture.controller.hostView(id)!!
                assertTrue("native view is mounted", view.height > 0 && view.width > 0)
                view.evaluateJavascript(
                    "document.querySelector('main').getBoundingClientRect().height / innerHeight",
                ) {
                    geometry = it
                    latch.countDown()
                }
            }
            assertTrue("geometry evaluation finished", latch.await(5, TimeUnit.SECONDS))
            assertTrue("page must fill its viewport, ratio=$geometry", (geometry.toDoubleOrNull() ?: 0.0) > 0.95)
        }
    }
}
