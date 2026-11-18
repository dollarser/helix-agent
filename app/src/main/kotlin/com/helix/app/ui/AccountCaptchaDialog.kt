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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val supported =
        remember {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }
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
    AccountCaptchaWindow(onDismiss = {
        policy.close()
        onDismiss()
    }) {
        if (!supported || failed) {
            AccountCaptchaError(supported) {
                policy.close()
                onDismiss()
            }
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
                            onDismiss,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountCaptchaError(
    supported: Boolean,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 400.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.account_captcha_title))
                val message = if (supported) R.string.account_captcha_failed else R.string.account_captcha_upgrade
                Text(stringResource(message))
                TextButton(onDismiss) { Text(stringResource(R.string.account_captcha_cancel)) }
            }
        }
    }
}

/** The trusted page owns its card and cancel action; host Back remains available even while loading. */
@Composable
@Suppress("FunctionName")
internal fun AccountCaptchaWindow(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent, content = content)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureCaptchaWebView(
    view: WebView,
    policy: AccountCaptchaPolicy,
    failed: () -> Unit,
    onDestroyed: (WebView) -> Unit,
    onProof: (AccountCaptchaProof) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        policy.close()
        failed()
        return
    }
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // The hosted challenge has fixed internal dimensions; retain page zoom for accessibility.
        textZoom = 100
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                if (policy.receiveCancellation(origin.toString(), mainFrame, source.url, message.data)) {
                    onDismiss()
                } else {
                    policy.receive(origin.toString(), mainFrame, source.url, message.data)?.let(onProof)
                }
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
