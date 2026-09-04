package com.helix.feature.browser.snapshot

/**
 * The result of one `browser.snapshot` (HXA-061; doc 09 §3.3). A BOUNDED semantic tree of
 * the tab's current page plus the metadata a [SnapshotNode.token] is bound to.
 *
 * Every page-derived string is wrapped in [UntrustedWebContent] (doc 09 §3.4): the tree is
 * DATA for the model, and instruction-like text in it authorizes no Tool call. [fingerprint]
 * is the SHA-256 over the canonical tree, computed by the TRUSTED host — a page cannot
 * forge it, and it is the DOM-change half of token invalidation (doc 09 §3.3: 导航、刷新、
 * DOM 大变化或超时都会使 token 失效).
 */
data class BrowserSnapshot(
    val tabId: String,
    val url: UntrustedWebContent,
    val title: UntrustedWebContent,
    val origin: String,
    val navigationGeneration: Long,
    val fingerprint: String,
    val mintedAtMillis: Long,
    val truncated: Boolean,
    val nodeCount: Int,
    val nodes: List<SnapshotNode>,
)

/**
 * One bounded node of a [BrowserSnapshot]. [text] / [value] / [href] / [name] are
 * page-derived and therefore [UntrustedWebContent]. A password field's value is always
 * null (the fixed script never reads it, doc 09 §3.3 密码框默认拒绝).
 *
 * [token] is the short-lived node token the host minted for this node; it is the ONLY
 * handle the HXA-062 `browser.click`/`browser.type` tools may use, and it is bound to
 * (tab, origin, navigation generation, fingerprint, TTL).
 */
data class SnapshotNode(
    val index: Int,
    val tag: String,
    val role: String,
    val text: UntrustedWebContent,
    val value: UntrustedWebContent?,
    val href: UntrustedWebContent?,
    val name: UntrustedWebContent?,
    val token: String,
)

/**
 * A host-minted, short-lived node token (doc 09 §3.3). It is bound to the tab, the page
 * origin, the navigation generation, the tree [fingerprint] and a [ttlMillis]; any one of
 * those drifting makes the token stale. The host is the sole minter and validator — a
 * model can only echo back tokens it was given, never forge a valid one, because validity
 * is checked against the host's live tab state and last snapshot fingerprint, not against
 * anything the token itself can assert.
 */
data class NodeToken(
    val version: Int,
    val nodeIndex: Int,
    val tabId: String,
    val origin: String,
    val navigationGeneration: Long,
    val fingerprint: String,
    val mintedAtMillis: Long,
    val ttlMillis: Long,
) {
    fun expired(nowMillis: Long): Boolean = nowMillis - mintedAtMillis > ttlMillis
}
