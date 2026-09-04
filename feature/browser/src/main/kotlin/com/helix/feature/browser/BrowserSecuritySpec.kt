package com.helix.feature.browser

/**
 * The hardening spec every Helix WebView must run with (HXA-060; doc 09 §3.4 and
 * AGENTS: "WebView is owned by the browser feature. Never register a privileged
 * permanent JavaScript bridge on untrusted pages.").
 *
 * [com.helix.feature.browser.webview.WebViewTabHost] applies [DEFAULT] at construction;
 * the on-device test rebuilds the spec from the live [android.webkit.WebSettings] and
 * runs [assertHardened] on it, so a future setting regression fails the gate instead of
 * shipping. Every field here is one the platform API can read back from a running
 * WebView (API 36); the two settings with no read-back — geolocation (setter-only) and
 * popup windows — are enforced beside the spec: `setGeolocationEnabled(false)` is an
 * explicit call in the host whose behavior the device test probes (a page-side
 * `navigator.geolocation` request must fail with `PERMISSION_DENIED`), and
 * `setSupportMultipleWindows(false)` is asserted on the live [android.webkit.WebSettings].
 *
 * Java/JavaScript stays ON because pages need it — the binding constraint is
 * that NO `addJavascriptInterface` call exists anywhere in the browser feature, which
 * this spec cannot express and the code review gate plus the device test
 * (`typeof window.helix === "undefined"`) do.
 */
data class BrowserSecuritySpec(
    val javaScriptEnabled: Boolean,
    val fileAccessEnabled: Boolean,
    val contentUrlAccessEnabled: Boolean,
    val fileAccessFromFileUrls: Boolean,
    val universalFileAccessFromFileUrls: Boolean,
    val mixedContentAllowed: Boolean,
    val safeBrowsingEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val databaseEnabled: Boolean,
    val javaScriptCanOpenWindowsAutomatically: Boolean,
) {
    companion object {
        val DEFAULT =
            BrowserSecuritySpec(
                javaScriptEnabled = true,
                fileAccessEnabled = false,
                contentUrlAccessEnabled = false,
                fileAccessFromFileUrls = false,
                universalFileAccessFromFileUrls = false,
                mixedContentAllowed = false,
                safeBrowsingEnabled = true,
                domStorageEnabled = true,
                databaseEnabled = false,
                javaScriptCanOpenWindowsAutomatically = false,
            )

        /** Fails with the first violated invariant; used by the on-device gate. */
        fun assertHardened(spec: BrowserSecuritySpec) {
            require(spec.javaScriptEnabled) { "javaScriptEnabled must be true" }
            require(!spec.fileAccessEnabled) { "fileAccessEnabled must be false" }
            require(!spec.contentUrlAccessEnabled) { "contentUrlAccessEnabled must be false" }
            require(!spec.fileAccessFromFileUrls) { "fileAccessFromFileUrls must be false" }
            require(!spec.universalFileAccessFromFileUrls) { "universalFileAccessFromFileUrls must be false" }
            require(!spec.mixedContentAllowed) { "mixedContentAllowed must be false" }
            require(spec.safeBrowsingEnabled) { "safeBrowsingEnabled must be true" }
            require(spec.domStorageEnabled) { "domStorageEnabled must be true" }
            require(!spec.databaseEnabled) { "databaseEnabled must be false" }
            require(
                !spec.javaScriptCanOpenWindowsAutomatically,
            ) { "javaScriptCanOpenWindowsAutomatically must be false" }
        }
    }
}
