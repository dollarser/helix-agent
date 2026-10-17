package com.helix.feature.browser

/**
 * WebView → controller callback surface (HXA-060). A [com.helix.feature.browser.webview.WebViewTabHost]
 * emits these events; the [BrowserController] binds each one to a tab id and applies it to the
 * pure [BrowserTabController]. Every callback arrives on the main thread.
 *
 * Page requests use the shared URL policy before continuing in their original frame.
 * [onBlockedNavigationCommitted] invalidates a forbidden document if a platform navigation
 * bypassed that request callback; it never replays an allowed request.
 */
internal interface BrowserTabListener {
    fun onPageStarted(url: String)

    fun onProgressChanged(progress: Int) {}

    fun onPageFinished(
        url: String,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    )

    /**
     * The tab's own document failed to load. [netError] is the Chromium `ERR_*` code
     * (0 when the platform did not expose one); [clientError] is the platform
     * `WebViewClient.ERROR_*` code.
     */
    fun onMainFrameError(
        netError: Int,
        clientError: Int,
        failingUrl: String?,
    )

    /**
     * A host failure without a platform error code. Modern resource errors use
     * [onMainFrameError]; this fallback preserves an existing typed error or explicit stop.
     */
    fun onMainFrameUnknownError(failingUrl: String?)

    /** The renderer exited; the host has already detached and destroyed its WebView. */
    fun onRendererGone(failingUrl: String?) = onMainFrameUnknownError(failingUrl)

    /** TLS failure: the host already cancelled the error handler (it never proceeds). */
    fun onSslError(failingUrl: String)

    /** A forbidden URL reached the committed-document callback; the host already stopped it. */
    fun onBlockedNavigationCommitted(url: String)

    /** A page-initiated download request (androidx.webkit download seam). */
    fun onDownloadRequest(request: DownloadRequest)

    /** User long-pressed a link or image element in the page. */
    fun onContextMenu(contextMenu: ContextMenuData) {}
}

sealed interface ContextMenuData {
    data class Link(
        val url: String,
    ) : ContextMenuData

    data class Image(
        val imageUrl: String,
        val linkUrl: String? = null,
    ) : ContextMenuData
}
