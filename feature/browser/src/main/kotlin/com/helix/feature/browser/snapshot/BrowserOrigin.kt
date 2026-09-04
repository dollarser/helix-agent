package com.helix.feature.browser.snapshot

import java.net.URI
import java.net.URISyntaxException

/**
 * Origin derivation for snapshot token binding (HXA-061). Operates only on URLs the
 * [com.helix.feature.browser.BrowserUrlPolicy] already admitted (http(s) with a host,
 * `about:blank`, `data:text/html`), which is exactly what a tab can hold.
 *
 * `data:` documents have an opaque origin per spec; they are bound to the constant
 * [DATA_OPAQUE] so tokens minted on one data: page still fail closed after the tab moves
 * on (the generation binding covers the same event, but the origin binding stays honest
 * instead of comparing two different opaque URLs).
 */
object BrowserOrigin {
    const val DATA_OPAQUE = "data:opaque"
    const val ABOUT_BLANK = "about:blank"

    /** Returns the canonical origin string for [url], or null when it is not origin-bearing. */
    fun of(url: String): String? =
        when {
            url == ABOUT_BLANK -> ABOUT_BLANK
            url.startsWith("data:") -> DATA_OPAQUE
            else -> httpOrigin(url)
        }

    @Suppress("ReturnCount", "SwallowedException")
    private fun httpOrigin(url: String): String? {
        val uri: URI =
            try {
                URI(url)
            } catch (e: URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port =
            when (uri.port) {
                -1, if (scheme == "http") 80 else 443 -> ""
                else -> ":${uri.port}"
            }
        return "$scheme://$host$port"
    }
}
