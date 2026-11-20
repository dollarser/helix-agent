package com.helix.app.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountCaptchaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Geometry and host dismissal only. Never click, solve or submit a real challenge. */
class AccountCaptchaViewportDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageReceivesWholeWindowWithoutAnotherCancelRow() {
        var view: WebView? = null
        compose.setContent {
            MaterialTheme {
                AccountCaptchaWindow({}) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).also {
                                view = it
                                it.loadDataWithBaseURL(
                                    null,
                                    "<html><body style='margin:0'><button>Page cancel</button></body></html>",
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitUntil(10_000) { view?.width?.let { it > 0 } == true }
        compose.runOnIdle {
            val page = requireNotNull(view)
            assertEquals(page.rootView.width, page.width)
            assertTrue(page.height >= page.rootView.height * 0.8)
        }
    }

    @Test fun systemBackDismissesLoadingPageExactlyOnce() {
        val visible = mutableStateOf(true)
        val calls = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    AccountCaptchaWindow({
                        calls.incrementAndGet()
                        visible.value = false
                    }) { Text("Synthetic loading page") }
                } else {
                    Text("Closed")
                }
            }
        }
        compose.onNodeWithText("Synthetic loading page").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Closed").assertIsDisplayed()
        assertEquals(1, calls.get())
    }

    @Test fun liveChallengeOnlyRendersThenClosesThroughSystemBack() {
        check(InstrumentationRegistry.getArguments().getString("captchaLayoutLive") == "true")
        val visible = mutableStateOf(true)
        val closed = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    AccountCaptchaDialog(AccountCaptchaAction.PASSWORD_RESET, {
                        closed.incrementAndGet()
                        visible.value = false
                    }, { /* No proof is submitted or recorded by this layout-only fixture. */ })
                } else {
                    Text("Closed")
                }
            }
        }
        val rendered = AtomicBoolean()
        val requested = AtomicBoolean()
        compose.waitUntil(45_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                WindowInspector
                    .getGlobalWindowViews()
                    .flatMap(::webViews)
                    .firstOrNull {
                        it.progress == 100 && it.url == AccountCaptchaPolicy(AccountCaptchaAction.PASSWORD_RESET).url
                    }?.let { page ->
                        assertEquals(100, page.settings.textZoom)
                        page.evaluateJavascript(
                            "Boolean(document.querySelector('#captcha-dialog[open]') || " +
                                "(document.querySelector('#captcha-page-start:not([hidden])') && " +
                                "!document.querySelector('#captcha-page-start').disabled))",
                        ) { hasContent ->
                            if (hasContent == "true" && requested.compareAndSet(false, true)) {
                                page.postVisualStateCallback(
                                    1,
                                    object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) {
                                            page.postOnAnimation { page.postOnAnimation { rendered.set(true) } }
                                        }
                                    },
                                )
                            }
                        }
                    }
            }
            rendered.get()
        }
        // The host document finishing is not evidence that the asynchronous challenge has rendered.
        android.os.SystemClock.sleep(2500)
        captureLivePage()
        assertChallengeContentFits()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Closed").assertIsDisplayed()
        assertEquals(1, closed.get())
    }

    private fun captureLivePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val measured = AtomicBoolean()
        instrumentation.runOnMainSync {
            WindowInspector.getGlobalWindowViews().flatMap(::webViews).single().evaluateJavascript(
                "JSON.stringify({width:innerWidth,height:innerHeight,visualHeight:visualViewport.height," +
                    "hostScripts:Array.from(document.scripts).map(s=>s.src).filter(s=>s.startsWith(location.origin))," +
                    "hostStyles:Array.from(document.querySelectorAll('link[rel=stylesheet]')).map(s=>s.href)," +
                    "elements:['html','body','#captcha-dialog','#captcha-dialog h2'," +
                    "'#captcha-widget','#captcha-cancel'].map(s=>{const e=document.querySelector(s);" +
                    "return {selector:s,rect:e.getBoundingClientRect().toJSON()," +
                    "display:getComputedStyle(e).display,maxHeight:getComputedStyle(e).maxHeight," +
                    "scrollTop:e.scrollTop,scrollHeight:e.scrollHeight}})})",
            ) { geometry ->
                File(instrumentation.targetContext.cacheDir, "captcha-layout-geometry.json").writeText(geometry)
                measured.set(true)
            }
        }
        compose.waitUntil(5000) { measured.get() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, "captcha-layout-live.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertChallengeContentFits() {
        val fits = AtomicBoolean()
        compose.waitUntil(5000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap(::webViews).single().evaluateJavascript(
                    "(()=>{const d=document.querySelector('#captcha-dialog');" +
                        "const r=d.getBoundingClientRect();return !document.querySelector('main') && " +
                        "!document.querySelector('#captcha-native-cancel') && d.open && r.height>100 && r.left>=0 && " +
                        "r.right<=innerWidth+1 && r.top>=0 && r.bottom<=visualViewport.height+1 && " +
                        "['h2','#captcha-widget','#captcha-cancel'].every(s=>{" +
                        "const x=d.querySelector(s).getBoundingClientRect();" +
                        "return x.height>0 && x.top>=r.top && x.bottom<=r.bottom})})()",
                ) { fits.set(it == "true") }
            }
            fits.get()
        }
    }

    private fun webViews(view: View): List<WebView> =
        when (view) {
            is WebView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
            else -> emptyList()
        }
}
