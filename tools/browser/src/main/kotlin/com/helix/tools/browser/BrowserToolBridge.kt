package com.helix.tools.browser

/**
 * The synchronous port the `browser.*` tools (HXA-062) execute against.
 *
 * Production is the :feature:browser `BrowserToolBridgeImpl` (wrapping the `BrowserController`);
 * unit tests inject a fake. The port is PURE JVM (no Android types) and returns small, bounded,
 * no-null view objects; each tool maps one outcome to a `Completed` / `Failed` ToolResult.
 *
 * Every method is fail-closed and bounded: an unknown tab is a stable NO_TAB outcome, a stale
 * node token is a STALE_TOKEN outcome, a password / payment / verification field is a REFUSED
 * outcome, and an internal deadline is a TIMED_OUT outcome. The port never throws for a page or
 * model condition (only for a genuine programming error).
 *
 * Node tokens (doc 09 §3.3): `browser.click` / `browser.type` may ONLY consume the short-lived
 * node token minted by the most recent `browser.snapshot`. The port re-validates the token
 * against the tab's LIVE state (origin / navigation generation / snapshot fingerprint / TTL)
 * before acting; a navigation, refresh, DOM change or TTL expiry makes it stale.
 */
@Suppress("TooManyFunctions")
interface BrowserToolBridge {
    /** Opens a new tab, selects it and navigates it to [url] (a blank [url] leaves a blank tab). */
    fun open(url: String): OpenOutcome

    /** Navigates [tabId] to [url] through the URL policy choke point (doc 09 §3.4). */
    fun navigate(
        tabId: String,
        url: String,
    ): NavigateOutcome

    /** History back within [tabId]. */
    fun back(tabId: String): HistoryOutcome

    /** History forward within [tabId]. */
    fun forward(tabId: String): HistoryOutcome

    /** Reloads [tabId]'s committed page. */
    fun reload(tabId: String): ReloadOutcome

    /** The bounded semantic-tree snapshot (the source of the node tokens click/type consume). */
    fun snapshot(tabId: String): SnapshotOutcome

    /** Case-insensitive substring search over the most recent snapshot of [tabId]. */
    fun find(
        tabId: String,
        query: String,
    ): FindOutcome

    /** Clicks the node named by [token]; the token is validated against live tab state first. */
    fun click(
        tabId: String,
        token: String,
    ): ActionOutcome

    /** Types [text] into the field named by [token]; sensitive fields are refused (doc 09 §3.3). */
    fun type(
        tabId: String,
        token: String,
        text: String,
    ): ActionOutcome

    /** Bounded viewport scroll of [tabId] by [dx] / [dy] CSS pixels. */
    fun scroll(
        tabId: String,
        dx: Int,
        dy: Int,
    ): ScrollOutcome

    /** Captures [tabId]'s WebView to a PNG and saves it to the Workspace (doc 09 §3.3). */
    fun screenshot(tabId: String): ScreenshotOutcome

    /**
     * Downloads [url] (manual, per-hop re-validated redirects) into the Workspace (doc 09 §3.4).
     * [suggestedName] is a fallback filename hint; the server's Content-Disposition and the URL
     * path take precedence. The stream is capped at the policy's byte ceiling and only executable
     * / installable types (APK/DEX/JAR/SO) or over-limit sizes are refused.
     */
    fun download(
        url: String,
        suggestedName: String,
    ): DownloadOutcome
}

/** Outcome of [BrowserToolBridge.open]. [url] / [origin] describe the resulting tab. */
data class OpenOutcome(
    val tabId: String,
    val url: String,
    val origin: String,
)

enum class NavStatus { STARTED, DENIED, NO_TAB, TIMED_OUT }

/** Outcome of [BrowserToolBridge.navigate]. [url] / [origin] are "" and [reason] is set on denial. */
data class NavigateOutcome(
    val status: NavStatus,
    val url: String,
    val origin: String,
    val reason: String,
)

enum class HistStatus { MOVED, NO_CHANGE, NO_TAB, TIMED_OUT }

/** Outcome of [BrowserToolBridge.back] / [BrowserToolBridge.forward]. */
data class HistoryOutcome(
    val status: HistStatus,
    val url: String,
    val origin: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val reason: String,
)

enum class ReloadStatus { RELOADED, NO_CHANGE, NO_TAB, TIMED_OUT }

/** Outcome of [BrowserToolBridge.reload]. */
data class ReloadOutcome(
    val status: ReloadStatus,
    val url: String,
    val origin: String,
    val reason: String,
)

/** One bounded node of a [SnapshotOutcome]. All page-derived strings are DATA, not instruction. */
data class BrowserNodeView(
    val index: Int,
    val role: String,
    val text: String,
    val value: String,
    val href: String,
    val name: String,
    val token: String,
)

/**
 * Outcome of [BrowserToolBridge.snapshot]. When [ok] is false, [message] is the fail-closed
 * reason and the tree fields are empty / zero. [fingerprint] is the host-computed tree hash the
 * node tokens are bound to.
 */
data class SnapshotOutcome(
    val ok: Boolean,
    val tabId: String,
    val url: String,
    val title: String,
    val origin: String,
    val navigationGeneration: Long,
    val fingerprint: String,
    val truncated: Boolean,
    val nodeCount: Int,
    val nodes: List<BrowserNodeView>,
    val message: String,
)

/** One [BrowserToolBridge.find] hit within the most recent snapshot. */
data class BrowserFindMatch(
    val index: Int,
    val role: String,
    val text: String,
    val token: String,
)

/** Outcome of [BrowserToolBridge.find]. [ok] is true when the tab has a snapshot to search. */
data class FindOutcome(
    val ok: Boolean,
    val tabId: String,
    val query: String,
    val matchCount: Int,
    val matches: List<BrowserFindMatch>,
    val message: String,
)

enum class ActionStatus { PERFORMED, REFUSED, STALE_TOKEN, NO_TAB, TIMED_OUT, ERROR }

/**
 * Outcome of [BrowserToolBridge.click] / [BrowserToolBridge.type]. [reason] carries the refusal
 * category (`password` / `payment` / `one-time-code` / `not-a-field`), the token-verdict name for
 * a stale token, or an error message; it is "" for a performed action.
 */
data class ActionOutcome(
    val status: ActionStatus,
    val nodeIndex: Int,
    val tag: String,
    val role: String,
    val reason: String,
)

enum class ScrollStatus { SCROLLED, NO_PAGE, NO_TAB, TIMED_OUT, ERROR }

/** Outcome of [BrowserToolBridge.scroll]. */
data class ScrollOutcome(
    val status: ScrollStatus,
    val dx: Int,
    val dy: Int,
    val reason: String,
)

enum class ScreenshotStatus { SAVED, NO_PAGE, NO_TAB, TIMED_OUT, ERROR }

/** Outcome of [BrowserToolBridge.screenshot]. [reference] is the model-safe Workspace reference. */
data class ScreenshotOutcome(
    val status: ScreenshotStatus,
    val reference: String,
    val sizeBytes: Long,
    val sha256: String,
    val reason: String,
)

enum class DownloadToolStatus { SAVED, REFUSED, TIMED_OUT, ERROR }

/**
 * Outcome of [BrowserToolBridge.download]. [fileName] / [reference] / [sizeBytes] / [sha256]
 * are empty / zero when nothing was saved; [reason] carries the refusal category (`url` /
 * `type` / `size` / `name` / `redirect` / `redirect-loop` / `redirect-limit` / `http-<code>` /
 * `size-exceeded`) or an error note, and is "" for a saved file. [finalUrl] is the URL actually
 * fetched after redirects ("" when refused before the network). [contentType] is the
 * server-declared MIME ("" when absent).
 */
data class DownloadOutcome(
    val status: DownloadToolStatus,
    val fileName: String,
    val finalUrl: String,
    val reference: String,
    val sizeBytes: Long,
    val sha256: String,
    val contentType: String,
    val reason: String,
)
