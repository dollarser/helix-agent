package com.helix.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * One dialog-owned view, fixed bundled JS, data-only LaTeX, no bridge or external navigation.
 * Created only by AndroidView with immutable document/callbacks, never inflated from XML.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class FormulaPreviewWebView(
    context: Context,
    private val document: String,
    private val height: (Float) -> Unit,
    private val status: (String) -> Unit,
) : WebView(context) {
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            blockNetworkLoads = true
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        webViewClient = PreviewClient()
        // Presentation never downloads user or remote content.
        setDownloadListener { _, _, _, _, _ -> }
    }

    // WebKit 1.17 constructor detector flags Kotlin super calls even with the override below.
    // Keep the real renderer-failure callback; device regression verifies its error/release path.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class PreviewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ) = true

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse =
            when {
                closed || request.method != "GET" -> {
                    blocked()
                }

                request.isForMainFrame && request.url.toString() == FormulaPreviewPolicy.PAGE -> {
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(document.toByteArray()))
                }

                else -> {
                    FormulaPreviewPolicy.asset(request.url.toString())?.let(::local) ?: blocked()
                }
            }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            if (!closed) status("loading")
        }

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) {
            observe(0)
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (!closed) status("error")
            return true
        }
    }

    private fun observe(attempt: Int) {
        if (closed) return
        evaluateJavascript("document.body.dataset.state") { result ->
            if (!closed) {
                when (result) {
                    "\"ready\"" -> measure()
                    "\"error\"" -> status("error")
                    else -> if (attempt < 30) handler.postDelayed({ observe(attempt + 1) }, 100) else status("error")
                }
            }
        }
    }

    private fun measure() {
        evaluateJavascript(
            "Math.ceil(document.getElementById('formula').getBoundingClientRect().height+24)",
        ) { result ->
            if (!closed) {
                val value = result.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }
                if (value == null) {
                    status("error")
                } else {
                    height(value)
                    status("ready")
                }
            }
        }
    }

    private fun local(path: String): WebResourceResponse =
        try {
            val mime =
                when {
                    path.endsWith(".js") -> "application/javascript"
                    path.endsWith(".css") -> "text/css"
                    path.endsWith(".woff2") -> "font/woff2"
                    path.endsWith(".woff") -> "font/woff"
                    else -> "font/ttf"
                }
            WebResourceResponse(mime, "UTF-8", context.assets.open(path))
        } catch (_: java.io.IOException) {
            blocked()
        }

    private fun blocked() =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(byteArrayOf()),
        )

    fun release() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        stopLoading()
        onPause()
        destroy()
    }
}
