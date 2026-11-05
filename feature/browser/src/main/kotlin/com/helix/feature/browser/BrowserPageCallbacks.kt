package com.helix.feature.browser

/** Each callback retains its original owner identity; retired pages cannot mutate current tabs. */
internal class BrowserPageCallbacks(
    private val tabs: BrowserTabController,
    private val changed: () -> Unit,
    private val recordHistory: (String, String) -> Unit,
    private val invalidateSnapshot: (String) -> Unit,
    private val download: (DownloadRequest) -> Unit,
    private val contextMenu: (ContextMenuData) -> Unit,
) {
    fun listener(
        id: String,
        owner: BrowserViewOwner,
        live: () -> Boolean,
    ): BrowserTabListener =
        object : BrowserTabListener {
            override fun onPageStarted(url: String) {
                if (!live()) return
                tabs.onPageStarted(id, url)
                changed()
            }

            override fun onProgressChanged(progress: Int) {
                if (!live()) return
                tabs.onProgressChanged(id, progress)
                changed()
            }

            override fun onPageFinished(
                url: String,
                title: String?,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                if (!live()) return
                tabs.onPageFinished(id, url, title, canGoBack, canGoForward)
                val tab = tabs.state().tabs.firstOrNull { it.id == id }
                if (tab?.isIncognito == false && url.isNotBlank() && url != BrowserTabController.ABOUT_BLANK) {
                    recordHistory(title ?: url, url)
                }
                changed()
            }

            override fun onMainFrameError(
                netError: Int,
                clientError: Int,
                failingUrl: String?,
            ) {
                if (!live()) return
                tabs.onMainFrameError(id, netError, clientError, failingUrl)
                changed()
            }

            override fun onMainFrameUnknownError(failingUrl: String?) {
                if (!live()) return
                tabs.onMainFrameUnknownError(id, failingUrl)
                changed()
            }

            override fun onRendererGone(failingUrl: String?) {
                if (!live()) return
                owner.hosts.remove(id)
                invalidateSnapshot(id)
                tabs.onMainFrameError(id, 0, android.webkit.WebViewClient.ERROR_UNKNOWN, failingUrl)
                changed()
            }

            override fun onSslError(failingUrl: String) {
                if (!live()) return
                tabs.onSslError(id, failingUrl)
                changed()
            }

            override fun onBlockedNavigationCommitted(url: String) {
                if (!live()) return
                // This is a committed forbidden document, not a cancelled request.
                if (BrowserUrlPolicy.evaluate(url) !is BrowserUrlDecision.Denied) return
                tabs.navigate(id, url)
                invalidateSnapshot(id)
                changed()
            }

            override fun onDownloadRequest(request: DownloadRequest) {
                if (live()) download(request)
            }

            override fun onContextMenu(contextMenu: ContextMenuData) {
                if (live()) this@BrowserPageCallbacks.contextMenu(contextMenu)
            }
        }
}
