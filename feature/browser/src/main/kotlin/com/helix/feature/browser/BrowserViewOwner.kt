package com.helix.feature.browser

import android.app.Activity
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
    ): WebViewTabHost = WebViewTabHost(checkNotNull(activity), listener, canShowDialogs)

    internal fun pause() {
        resumed = false
        hosts.values.toList().forEach { it.pause() }
    }

    internal fun resume() {
        resumed = true
        hosts.values.toList().forEach { it.resume() }
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
