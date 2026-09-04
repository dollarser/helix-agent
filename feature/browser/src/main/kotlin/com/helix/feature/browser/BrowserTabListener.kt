package com.helix.feature.browser

/**
 * WebView → controller callback surface (HXA-060). A [com.helix.feature.browser.webview.WebViewTabHost]
 * emits these events; the [BrowserController] binds each one to a tab id and applies it to the
 * pure [BrowserTabController]. Every callback arrives on the main thread.
 *
 * [onNavigationAttempt] is how the WebView's own navigations (links, form submits, `location`
 * changes, redirects) are re-routed through the policy choke point: the controller calls
 * [BrowserTabController.navigate] again, so a page can never load a URL the user did not
 * admit through the same gate (doc 09 §3.4).
 */
internal interface BrowserTabListener {
    fun onPageStarted(url: String)

    fun onPageFinished(
        url: String,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    )

    /**
     * The tab's own document failed to load. [netError] is the Chromium `ERR_*` code
     * (0 when the platform did not expose one); [clientError] is the legacy
     * `WebViewClient.ERROR_*` code.
     */
    fun onMainFrameError(
        netError: Int,
        clientError: Int,
        failingUrl: String?,
    )

    /**
     * The tab's own document failed to load with NO numeric code (the modern
     * `WebViewClient.onReceivedError(WebResourceRequest, WebResourceError)` callback, which
     * the current System WebView fires for main-frame failures). The state machine surfaces
     * an UNKNOWN error page unless a typed [onMainFrameError] already set one or the tab was
     * stopped by the user — the codeless callback cannot tell an aborted load from a real
     * failure, and the stop is the only case that must stay a non-error.
     */
    fun onMainFrameUnknownError(failingUrl: String?)

    /** TLS failure: the host already cancelled the error handler (it never proceeds). */
    fun onSslError(failingUrl: String)

    /** The WebView attempted to navigate to [url]; the controller re-admits it through policy. */
    fun onNavigationAttempt(url: String)

    /** A page-initiated download request (androidx.webkit download seam). */
    fun onDownloadRequest(request: DownloadRequest)
}
