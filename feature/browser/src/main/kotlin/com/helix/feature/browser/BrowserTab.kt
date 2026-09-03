package com.helix.feature.browser

import java.net.URISyntaxException

/**
 * One Helix browser tab (HXA-060). A value snapshot — the mutable truth is
 * [BrowserTabController]; the Compose UI and the on-device tests read these copies.
 *
 * [url] is the CURRENT committed (or, while loading, target) URL; [error] is non-null
 * exactly while the tab shows its error page — the two views are mutually exclusive.
 * [navigationGeneration] bumps on every committed navigation; it is the token-binding
 * groundwork for HXA-061/062 (`BrowserTabScope` = tab id + navigation generation,
 * doc 09 §3.2/§3.3), so snapshot tokens minted for one generation are provably stale
 * after the next.
 */
data class BrowserTab(
    val id: String,
    val url: String = BrowserTabController.ABOUT_BLANK,
    val title: String? = null,
    val isLoading: Boolean = false,
    /**
     * True right after the user pressed stop. The codeless modern error callback cannot
     * tell an aborted load from a real failure, so a codeless UNKNOWN error page is
     * suppressed while this is set; it clears on the next real transition.
     */
    val stopped: Boolean = false,
    val error: BrowserError? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val navigationGeneration: Long = 0,
) {
    /** The short label for the tab strip: the title when the page set one, else the host. */
    val label: String
        get() =
            title?.takeIf { it.isNotBlank() }
                ?: urlDisplayHost(url)
                ?: (if (url == BrowserTabController.ABOUT_BLANK) "新标签页" else url)
}

/**
 * The host part of [url] for display only. Never throws: an unparseable value yields
 * null and the caller falls back. The raw URL is UNTRUSTED web content (doc 09 §3.4) —
 * only the host reaches UI strings.
 */
internal fun urlDisplayHost(url: String?): String? {
    if (url.isNullOrBlank() || url == BrowserTabController.ABOUT_BLANK) return null
    // An unparseable URL reaching the tab strip is a display problem, not an error state:
    // the swallowed exception and the null (caller falls back) are the same fail-closed
    // decision.
    @Suppress("SwallowedException")
    return try {
        java.net
            .URI(url)
            .host
            ?.takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: URISyntaxException) {
        // Same fail-closed fallback; libcore/this JVM throw the checked spelling.
        null
    }
}
