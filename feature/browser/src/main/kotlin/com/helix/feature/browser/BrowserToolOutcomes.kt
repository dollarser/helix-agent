package com.helix.feature.browser

// Feature-level outcomes the [BrowserController] command methods return for the HXA-062 tool
// bridge. These are the browser feature's OWN domain types; the port types declared in
// :tools:browser (`NavigateOutcome`, `HistoryOutcome`, ...) are produced by
// [BrowserToolBridgeImpl] mapping these, so the feature facade stays decoupled from the tool
// layer even though the module happens to depend on :tools:browser.
//
// A navigation's COMMITTED url / origin is only known once the WebView settles the load, so the
// back / forward / reload outcomes report the EVENT (moved / no-change) and the bridge reads the
// settled tab state afterwards.

/** Result of opening a new tab and, for an admitted [url], navigating it. */
data class BrowserOpenResult(
    val tabId: String,
    val url: String,
    val origin: String,
)

/** The outcome of a navigation attempt through the URL policy choke point. */
sealed interface BrowserNavResult {
    /** The URL was admitted and the WebView is loading it. */
    data class Started(
        val url: String,
        val origin: String,
    ) : BrowserNavResult

    /** The URL was denied by policy; the tab shows its policy error page (never loaded). */
    data class Denied(
        val reason: String,
    ) : BrowserNavResult

    /** No such tab. */
    data object NoTab : BrowserNavResult
}

/** The outcome of a history back / forward. */
sealed interface BrowserHistResult {
    /** The WebView started moving; the committed url is known once it settles. */
    data object Moved : BrowserHistResult

    /** Nothing to move to (no history entry, or still loading). */
    data class NoChange(
        val reason: String,
    ) : BrowserHistResult

    /** No such tab. */
    data object NoTab : BrowserHistResult
}

/** The outcome of a reload. */
sealed interface BrowserReloadResult {
    /** The WebView started reloading the committed page. */
    data object Reloaded : BrowserReloadResult

    /** Nothing to reload (blank tab, or still loading). */
    data class NoChange(
        val reason: String,
    ) : BrowserReloadResult

    /** No such tab. */
    data object NoTab : BrowserReloadResult
}

/**
 * The outcome of evaluating one fixed action script on a settled page (HXA-062
 * `browser.click` / `type` / `scroll`).
 */
sealed interface EvalFixedOutcome {
    /** The tab has no settled page to evaluate on (unknown / loading / error / never navigated). */
    data object NoPage : EvalFixedOutcome

    /** The evaluation returned; [raw] is the raw JSON text (null on eval failure / timeout). */
    data class Result(
        val raw: String?,
    ) : EvalFixedOutcome
}
