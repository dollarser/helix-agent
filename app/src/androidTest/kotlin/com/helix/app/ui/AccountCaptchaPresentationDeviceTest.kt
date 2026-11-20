package com.helix.app.ui

import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.helix.app.account.AccountCaptchaAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic trusted host DOM only. Never loads or interacts with a real challenge. */
class AccountCaptchaPresentationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun registrationCancelReturnsToNativeForm() = verify(AccountCaptchaAction.REGISTRATION)

    @Test fun resetCancelReturnsToNativeForm() = verify(AccountCaptchaAction.PASSWORD_RESET)

    @Test fun bindingCancelReturnsToNativeForm() = verify(AccountCaptchaAction.EMAIL_BINDING)

    private fun verify(action: AccountCaptchaAction) {
        val visible = mutableStateOf(true)
        val cancelled = AtomicInteger()
        val policy = AccountCaptchaPolicy(action)
        var page: WebView? = null
        compose.setContent {
            MaterialTheme {
                Text("Native account form")
                if (visible.value) {
                    AccountCaptchaWindow({ visible.value = false }) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                            WebView(context).also { view ->
                                page = view
                                prepare(view, policy) {
                                    cancelled.incrementAndGet()
                                    visible.value = false
                                }
                            }
                        })
                    }
                }
            }
        }
        assertScript({ page }, "document.querySelector('dialog').open && !document.querySelector('main')")
        val cancelScript = "document.getElementById('captcha-cancel').click()"
        compose.runOnIdle { page!!.evaluateJavascript(cancelScript, null) }
        compose.waitUntil(5000) { cancelled.get() == 1 }
        compose.onNodeWithText("Native account form").assertIsDisplayed()
        assertEquals(1, cancelled.get())
        compose.runOnIdle { page!!.destroy() }
    }

    private fun assertScript(
        page: () -> WebView?,
        expression: String,
    ) {
        val matches = AtomicBoolean()
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                page()?.evaluateJavascript("Boolean($expression)") { matches.set(it == "true") }
            }
            matches.get()
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun prepare(
        view: WebView,
        policy: AccountCaptchaPolicy,
        cancel: () -> Unit,
    ) {
        view.settings.javaScriptEnabled = true
        view.setBackgroundColor(Color.TRANSPARENT)
        val origins = setOf(AccountCaptchaPolicy.ORIGIN)
        WebViewCompat.addWebMessageListener(view, "HelixCaptcha", origins) { source, message, origin, mainFrame, _ ->
            val accepted = policy.receiveCancellation(origin.toString(), mainFrame, source.url, message.data)
            if (accepted) cancel()
        }
        view.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse {
                    val stream = ByteArrayInputStream(html.toByteArray())
                    return WebResourceResponse("text/html", "UTF-8", stream)
                }
            }
        view.loadUrl(policy.url)
    }

    private val html =
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{background:transparent}dialog{width:80%;max-width:400px}</style></head><body>
        <dialog id="captcha-dialog"><h2>Synthetic verification</h2><div id="captcha-widget">Fixture only</div>
        <button id="captcha-cancel">Cancel</button></dialog>
        <script>document.querySelector('dialog').showModal();
        document.getElementById('captcha-cancel').onclick=()=>HelixCaptcha.postMessage(JSON.stringify({
          type:'captcha-cancelled',action:new URL(location.href).searchParams.get('action')
        }));</script>
        </body></html>
        """.trimIndent()
}
