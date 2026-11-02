package com.helix.feature.browser

import android.app.Activity
import android.webkit.WebView
import com.helix.feature.browser.webview.WebViewTabHost

/** Activity-owned UI resources. The application facade keeps only a weak binding to this owner. */
class BrowserViewOwner(
    activity: Activity,
) {
    private var activity: Activity? = activity
    internal val hosts = HashMap<String, WebViewTabHost>()
    internal var resumed = false
        private set
    internal val available: Boolean
        get() = activity?.let { !it.isDestroyed && !it.isFinishing } == true

    internal fun create(
        listener: BrowserTabListener,
        canShowDialogs: () -> Boolean,
    ): WebViewTabHost {
        val current = checkNotNull(activity)
        return WebViewTabHost(current, listener, canShowDialogs).also { host ->
            // Tool-owned pages need a real viewport without selecting the user's browser tab.
            val window = current.window.decorView
            if (window.width > 0 && window.height > 0) {
                host.webView.measure(
                    android.view.View.MeasureSpec
                        .makeMeasureSpec(window.width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec
                        .makeMeasureSpec(window.height, android.view.View.MeasureSpec.EXACTLY),
                )
                host.webView.layout(0, 0, window.width, window.height)
            }
        }
    }

    internal fun pause() {
        resumed = false
        hosts.values.toList().forEach { it.pause() }
    }

    internal fun resume() {
        resumed = true
        hosts.values.toList().forEach { it.resume() }
    }

    /** Cache is shared by the application's WebViews, including closed tabs. No page is loaded. */
    internal fun clearCache() {
        check(available)
        val maintenanceView = WebView(checkNotNull(activity))
        try {
            WebViewTabHost.applyHardenedSettings(maintenanceView.settings)
            maintenanceView.clearCache(true)
        } finally {
            maintenanceView.destroy()
        }
    }

    internal fun clear() {
        val retired = hosts.values.toList()
        hosts.clear() // Invalidate callback identity before invoking platform cleanup.
        retired.forEach { it.destroy() }
    }

    /** Idempotent; even an externally retained retired owner no longer retains its Activity. */
    fun destroy() {
        resumed = false
        clear()
        activity = null
    }
}
