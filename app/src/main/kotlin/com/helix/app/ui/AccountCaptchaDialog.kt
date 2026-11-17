package com.helix.app.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.helix.app.R
import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof
import java.io.ByteArrayInputStream

internal typealias AccountCaptchaContent = @Composable (
    AccountCaptchaAction,
    () -> Unit,
    (AccountCaptchaProof) -> Unit,
) -> Unit

internal val defaultAccountCaptchaContent: AccountCaptchaContent = { action, cancel, complete ->
    AccountCaptchaDialog(action, cancel, complete)
}

@Composable
@Suppress("FunctionName")
internal fun AccountCaptchaDialog(
    action: AccountCaptchaAction,
    onDismiss: () -> Unit,
    onProof: (AccountCaptchaProof) -> Unit,
) {
    val policy = remember(action) { AccountCaptchaPolicy(action) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember { mutableStateOf(false) }
    val supported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    DisposableEffect(policy) {
        onDispose {
            policy.close()
            webView?.let {
                it.stopLoading()
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(it, "HelixCaptcha")
                }
                it.removeAllViews()
                it.destroy()
            }
        }
    }
    Dialog(onDismissRequest = {
        policy.close()
        onDismiss()
    }) {
        Surface {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.account_captcha_title))
                if (!supported || failed) {
                    Text(
                        stringResource(
                            if (supported) R.string.account_captcha_failed else R.string.account_captcha_upgrade,
                        ),
                    )
                } else {
                    AndroidView(
                        factory = { context ->
                            WebView(context).also { view ->
                                webView = view
                                configureCaptchaWebView(
                                    view,
                                    policy,
                                    { failed = true },
                                    { if (webView === it) webView = null },
                                    onProof,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 480.dp),
                    )
                }
                TextButton({
                    policy.close()
                    onDismiss()
                }) { Text(stringResource(R.string.account_captcha_cancel)) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureCaptchaWebView(
    view: WebView,
    policy: AccountCaptchaPolicy,
    failed: () -> Unit,
    onDestroyed: (WebView) -> Unit,
    onProof: (AccountCaptchaProof) -> Unit,
) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        policy.close()
        failed()
        return
    }
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    view.webChromeClient =
        object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
    view.webViewClient = captchaWebClient(policy, failed, onDestroyed)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(
            view,
            "HelixCaptcha",
            setOf(AccountCaptchaPolicy.ORIGIN),
        ) {
            source,
            message,
            origin,
            mainFrame,
            _,
            ->
            if (message.type == WebMessageCompat.TYPE_STRING) {
                policy.receive(origin.toString(), mainFrame, source.url, message.data)?.let(onProof)
            }
        }
    }
    view.loadUrl(policy.url)
}

internal fun captchaWebClient(
    policy: AccountCaptchaPolicy,
    failed: () -> Unit,
    onDestroyed: (WebView) -> Unit,
): WebViewClient = CaptchaWebViewClient(policy, failed, onDestroyed)

// WebKit 1.17's constructor visitor reports even a super call with the override below.
// AccountCaptchaRendererDeviceTest checks both crash/kill paths and late-proof rejection.
@SuppressLint("MissingOnRenderProcessGone")
private class CaptchaWebViewClient(
    private val policy: AccountCaptchaPolicy,
    private val failed: () -> Unit,
    private val onDestroyed: (WebView) -> Unit,
) : WebViewClient() {
    private fun fail() {
        policy.close()
        failed()
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        // Revoke delivery before teardown; queued messages from this renderer stay invalid.
        fail()
        onDestroyed(view)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        return true
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean =
        !policy
            .navigationAllowed(
                request.url.toString(),
                request.isForMainFrame,
            ).also { if (!it && request.isForMainFrame) fail() }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? =
        if (policy.navigationAllowed(request.url.toString(), request.isForMainFrame)) {
            null
        } else {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(byteArrayOf()),
            )
        }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        handler.cancel()
        fail()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) fail()
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        response: WebResourceResponse,
    ) {
        if (request.isForMainFrame) fail()
    }
}
