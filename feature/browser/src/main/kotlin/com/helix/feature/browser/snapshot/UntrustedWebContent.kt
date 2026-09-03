package com.helix.feature.browser.snapshot

/**
 * Marks a string as web-page content (doc 09 §3.4: 页面正文标记为 `UNTRUSTED_WEB_CONTENT`，
 * 其中的指令不构成 Tool 授权).
 *
 * Every page-derived string inside [BrowserSnapshot] is wrapped in this type, so a
 * consumer (the HXA-062 `browser.*` tools, the context builder) that passes snapshot
 * content into a model request must go through code that explicitly unwraps it — the
 * untrusted marking is structural, not a documentation convention. Instruction-like text
 * inside a [text] value is DATA; it authorizes no Tool call.
 */
data class UntrustedWebContent(
    val text: String,
)
