package com.helix.feature.browser.webview

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.helix.feature.browser.BrowserSecuritySpec
import com.helix.feature.browser.BrowserTabListener
import com.helix.feature.browser.BrowserUrlDecision
import com.helix.feature.browser.BrowserUrlPolicy
import com.helix.feature.browser.DownloadRequest

/**
 * One Helix tab's System WebView (HXA-060; doc 09 §3.4 WebView 安全约束). Constructed
 * hardened: [BrowserSecuritySpec.DEFAULT] is applied field-by-field in the constructor and
 * the on-device test rebuilds the spec from the live [WebSettings] and re-asserts it, so a
 * setting regression fails the verification gate instead of shipping.
 *
 * Hard rules this class enforces:
 * - `loadUrl` is reachable ONLY through [load], which the [com.helix.feature.browser.BrowserController]
 *   calls to apply a policy-allowed [com.helix.feature.browser.BrowserTabController.TabCommand.Load].
 * - EVERY page-initiated navigation is re-admitted through [BrowserTabListener.onNavigationAttempt]
 *   (the policy choke point) — links and forms via [WebViewClient.shouldOverrideUrlLoading],
 *   redirects / `location` changes via [WebViewClient.doUpdateVisitedHistory].
 * - There is NO `addJavascriptInterface` call in this class (or anywhere in the browser
 *   feature): untrusted web content never gets a privileged permanent JS bridge (AGENTS.md).
 * - TLS errors cancel (never proceed); Safe Browsing is left to the system interstitial
 *   (this class deliberately does NOT override `onSafeBrowsingHit`); camera / mic /
 *   geolocation requests are denied.
 */
internal class WebViewTabHost(
    context: Context,
    private val listener: BrowserTabListener,
) {
    val webView: WebView

    /** The URL the tab most recently started loading; the same-document check in the legacy error callback. */
    private var lastLoadUrl: String? = null

    private val client =
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Consume the navigation and re-admit it through the controller's policy choke
                // point: allowed → a fresh Load command reaches [load]; denied → the tab shows
                // its policy error page and the WebView is never asked to load the URL.
                listener.onNavigationAttempt(request.url.toString())
                return true
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String,
                isReload: Boolean,
            ) {
                // Server redirects and `location` changes do NOT pass through
                // shouldOverrideUrlLoading; fail closed on the committed URL too.
                if (BrowserUrlPolicy.evaluate(url) is BrowserUrlDecision.Denied) {
                    view.stopLoading()
                    listener.onNavigationAttempt(url)
                }
            }

            @SuppressLint("SetJavaScriptEnabled")
            @Deprecated("Legacy main-frame error callback: WebResourceError exposes no numeric code.")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                // The legacy callback also reports sub-resource (image/script) failures; the
                // same-document check keeps only failures of the tab's own document.
                if (failingUrl == null || isSameDocument(failingUrl, lastLoadUrl)) {
                    listener.onMainFrameError(0, errorCode, failingUrl)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // The callback the current System WebView actually fires for a main-frame
                // failure — but `WebResourceError` carries no numeric ERR_* code, so this is
                // the codeless path: the state machine maps it to an UNKNOWN error page, only
                // while the tab is still loading and no typed legacy error has landed. The
                // same-document check also drops ERR_ABORTED-style noise from loads that a
                // newer navigation already replaced.
                if (!request.isForMainFrame()) return
                val url = request.url.toString()
                if (!isSameDocument(url, lastLoadUrl)) return
                listener.onMainFrameUnknownError(url)
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                // doc 09 §3.4: a TLS failure is never proceeded past.
                handler.cancel()
                listener.onSslError(error.url)
            }

            override fun onPageStarted(
                view: WebView,
                url: String,
                favicon: android.graphics.Bitmap?,
            ) {
                lastLoadUrl = url
                listener.onPageStarted(url)
            }

            override fun onPageFinished(
                view: WebView,
                url: String,
            ) {
                listener.onPageFinished(url, view.title, view.canGoBack(), view.canGoForward())
            }
        }

    private val chromeClient =
        object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) {
                callback.invoke(origin, false, false) // 默认拒绝（doc 09 §3.4）
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny() // 摄像头 / 麦克风：默认拒绝（doc 09 §3.4）
            }
        }

    init {
        webView =
            WebView(context).also { view ->
                applyHardenedSettings(view.settings)
                view.settings.setSupportMultipleWindows(false) // 不允许页面弹出新窗口（doc 09 §3.2）
                view.webViewClient = client
                view.webChromeClient = chromeClient
                // The platform download seam (the only one remaining in androidx.webkit 1.17):
                // the WebView's own download UI never appears: the controller queues the item and
                // the user explicitly picks the destination (SAF CreateDocument) before any bytes
                // are written.
                view.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                    listener.onDownloadRequest(
                        DownloadRequest(
                            url = url,
                            suggestedName = suggestedNameFrom(contentDisposition, url),
                            mimeType = mimeType,
                            contentLength = if (contentLength < 0) -1L else contentLength,
                        ),
                    )
                }
            }
    }

    /** The ONLY loadUrl entry point in this class. */
    fun load(url: String) {
        lastLoadUrl = url
        webView.loadUrl(url)
    }

    fun goBack() = webView.goBack()

    fun goForward() = webView.goForward()

    fun reload() = webView.reload()

    fun stop() = webView.stopLoading()

    /** Stops JS timers / the compositor while the app is paused (doc 09 performance). */
    fun pause() = webView.onPause()

    fun resume() = webView.onResume()

    fun clearCache() = webView.clearCache(true)

    fun destroy() {
        webView.stopLoading()
        webView.loadUrl(ABOUT_BLANK)
        webView.destroy()
    }

    private fun isSameDocument(
        url: String,
        loaded: String?,
    ): Boolean {
        val base = loaded?.substringBefore('#') ?: return false
        return url.substringBefore('#') == base
    }

    companion object {
        const val ABOUT_BLANK = "about:blank"

        /**
         * Applies [spec] field-by-field to [settings]. [spec] must satisfy
         * [BrowserSecuritySpec.assertHardened] — the host always passes [BrowserSecuritySpec.DEFAULT],
         * so every field is set explicitly and none falls back to a platform default.
         */
        fun applyHardenedSettings(
            settings: WebSettings,
            spec: BrowserSecuritySpec = BrowserSecuritySpec.DEFAULT,
        ) {
            BrowserSecuritySpec.assertHardened(spec)
            settings.javaScriptEnabled = spec.javaScriptEnabled
            settings.allowFileAccess = spec.fileAccessEnabled
            settings.allowContentAccess = spec.contentUrlAccessEnabled
            settings.setAllowFileAccessFromFileURLs(spec.fileAccessFromFileUrls)
            settings.setAllowUniversalAccessFromFileURLs(spec.universalFileAccessFromFileUrls)
            settings.mixedContentMode =
                if (spec.mixedContentAllowed) {
                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            settings.safeBrowsingEnabled = spec.safeBrowsingEnabled
            settings.domStorageEnabled = spec.domStorageEnabled
            settings.databaseEnabled = spec.databaseEnabled
            settings.javaScriptCanOpenWindowsAutomatically = spec.javaScriptCanOpenWindowsAutomatically
            // Geolocation has no read-back getter (API 36): it is deliberately absent from
            // [BrowserSecuritySpec]. Hardened means OFF, so the call is explicit here and the
            // on-device test probes the behavior (a page-side request must be denied).
            settings.setGeolocationEnabled(false)
        }

        /**
         * Rebuilds a [BrowserSecuritySpec] from a live [WebSettings] so the on-device gate can
         * [BrowserSecuritySpec.assertHardened] what a real WebView actually runs with.
         */
        fun specFromSettings(settings: WebSettings): BrowserSecuritySpec =
            BrowserSecuritySpec(
                javaScriptEnabled = settings.javaScriptEnabled,
                fileAccessEnabled = settings.allowFileAccess,
                contentUrlAccessEnabled = settings.allowContentAccess,
                fileAccessFromFileUrls = settings.allowFileAccessFromFileURLs,
                universalFileAccessFromFileUrls = settings.allowUniversalAccessFromFileURLs,
                mixedContentAllowed = settings.mixedContentMode == WebSettings.MIXED_CONTENT_ALWAYS_ALLOW,
                safeBrowsingEnabled = settings.safeBrowsingEnabled,
                domStorageEnabled = settings.domStorageEnabled,
                databaseEnabled = settings.databaseEnabled,
                javaScriptCanOpenWindowsAutomatically = settings.javaScriptCanOpenWindowsAutomatically,
            )

        /**
         * The `Content-Disposition` filename forms servers actually send (`filename="a b.txt"`,
         * `filename=a-b.txt`); the platform `MimeTypeMap.getFileNameFromContentDispositionHeader`
         * is gone in API 36, so parse the two forms by hand. Fallback: the URL's last path
         * segment. Hostile values are harmless — [com.helix.feature.browser.BrowserDownloadPolicy.sanitizeName]
         * strips directory parts, control characters and leading dots before the name is shown.
         *
         * One early return per Content-Disposition form (quoted / bare) + the URL fallback.
         */
        @Suppress("ReturnCount")
        internal fun suggestedNameFrom(
            contentDisposition: String?,
            url: String,
        ): String {
            contentDisposition?.let { header ->
                val quoted = header.substringAfter("filename=\"", missingDelimiterValue = "").substringBefore('"')
                if (quoted.isNotBlank()) return quoted
                val bare =
                    header
                        .substringAfter("filename=", missingDelimiterValue = "")
                        .substringBefore(';')
                        .trim()
                        .trim('"')
                if (bare.isNotBlank()) return bare
            }
            return url.substringAfterLast('/').ifBlank { "download" }
        }
    }
}
