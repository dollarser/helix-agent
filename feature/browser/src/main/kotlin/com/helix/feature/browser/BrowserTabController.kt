package com.helix.feature.browser

import java.util.UUID

/**
 * The browser feature's tab state machine (HXA-060) — pure JVM, no Android types.
 *
 * It is the SINGLE admission point for every URL a tab may load: [navigate] consults
 * [BrowserUrlPolicy] and is the only path that turns a raw string into a [Load] command.
 * The Android layer ([WebViewTabHost]) may call `WebView.loadUrl` only to apply a command
 * this machine emitted (or to react to the WebView's own back/forward/reload, which never
 * introduce a new URL), so page content and user input cannot bypass the policy
 * (doc 09 §3.4).
 *
 * Single-threaded by contract (main thread on device, test thread in JVM tests); it holds
 * no locks. [state] returns immutable value copies.
 *
 * The tab state machine: one command method + one WebView callback per event; the count
 * is intrinsic to the machine, not a design smell.
 */
@Suppress("TooManyFunctions")
class BrowserTabController(
    val maxTabs: Int = DEFAULT_MAX_TABS,
) {
    init {
        require(maxTabs in 1..HARD_MAX_TABS) { "maxTabs must be in 1..$HARD_MAX_TABS, was $maxTabs" }
    }

    /** Immutable snapshot of all tabs and the selected one. */
    data class State(
        val tabs: List<BrowserTab> = emptyList(),
        val selectedId: String? = null,
    ) {
        val selectedTab: BrowserTab?
            get() = tabs.firstOrNull { it.id == selectedId }
    }

    /**
     * Work for the platform layer after a state mutation; `null` from a command method
     * means the guard rejected the call (e.g. nothing to go back to) and the WebView
     * must not be touched.
     */
    sealed interface TabCommand {
        /** Load [url] (already policy-allowed) into the tab. */
        data class Load(
            val tabId: String,
            val url: String,
        ) : TabCommand

        data class Back(
            val tabId: String,
        ) : TabCommand

        data class Forward(
            val tabId: String,
        ) : TabCommand

        data class Reload(
            val tabId: String,
        ) : TabCommand

        data class Stop(
            val tabId: String,
        ) : TabCommand

        /** The tab is closed; its WebView (if any) must be destroyed and dropped. */
        data class Destroy(
            val tabId: String,
        ) : TabCommand
    }

    private var state: State = State()

    fun state(): State = state

    // ---------------------------------------------------------------- commands

    /** Creates a blank tab, selects it, and returns its id. Fails at [maxTabs]. */
    fun newTab(): String {
        check(state.tabs.size < maxTabs) { "tab limit reached: $maxTabs" }
        val id = newTabId()
        state = state.copy(tabs = state.tabs + BrowserTab(id), selectedId = id)
        return id
    }

    /** Closes the tab; selection moves to the neighbor (or null when it was the last). */
    fun closeTab(id: String): TabCommand {
        val index = state.tabs.indexOfFirst { it.id == id }
        require(index >= 0) { "unknown tab: $id" }
        val tabs = state.tabs.toMutableList().apply { removeAt(index) }
        val selected =
            when {
                state.selectedId != id -> state.selectedId
                tabs.isEmpty() -> null
                index < tabs.size -> tabs[index].id
                else -> tabs[index - 1].id
            }
        state = state.copy(tabs = tabs, selectedId = selected)
        return TabCommand.Destroy(id)
    }

    fun select(id: String) {
        check(id in state.tabs.map { it.id }) { "unknown tab: $id" }
        state = state.copy(selectedId = id)
    }

    /**
     * The policy choke point. Allowed → the tab enters loading and a [Load] is returned;
     * denied → the tab shows its error page (bound to [PolicyBlockedError]) and `null`
     * is returned — the WebView is never asked to load a denied URL.
     */
    fun navigate(
        id: String,
        rawUrl: String,
    ): TabCommand? =
        when (val decision = BrowserUrlPolicy.evaluate(rawUrl)) {
            is BrowserUrlDecision.Allowed -> {
                val url = decision.url
                replaceTab(id) { it.copy(url = url, title = null, error = null, isLoading = true, stopped = false) }
                TabCommand.Load(id, url)
            }

            is BrowserUrlDecision.Denied -> {
                replaceTab(id) {
                    it.copy(error = PolicyBlockedError(rawUrl, decision.reason), isLoading = false, title = null)
                }
                null
            }
        }

    fun goBack(id: String): TabCommand? {
        val tab = requireTab(id)
        if (tab.isLoading || !tab.canGoBack) return null
        replaceTab(id) { it.copy(isLoading = true, error = null, stopped = false) }
        return TabCommand.Back(id)
    }

    fun goForward(id: String): TabCommand? {
        val tab = requireTab(id)
        if (tab.isLoading || !tab.canGoForward) return null
        replaceTab(id) { it.copy(isLoading = true, error = null, stopped = false) }
        return TabCommand.Forward(id)
    }

    /** No-op on a blank tab (nothing to reload) or while loading. */
    fun reload(id: String): TabCommand? {
        val tab = requireTab(id)
        if (tab.isLoading || tab.url == ABOUT_BLANK) return null
        replaceTab(id) { it.copy(isLoading = true, error = null, stopped = false) }
        return TabCommand.Reload(id)
    }

    /** No-op unless the tab is loading; a stopped load is not an error page. */
    fun stop(id: String): TabCommand? {
        val tab = requireTab(id)
        if (!tab.isLoading) return null
        replaceTab(id) { it.copy(isLoading = false, error = null, stopped = true) }
        return TabCommand.Stop(id)
    }

    // ---------------------------------------------------------------- WebView callbacks

    fun onPageStarted(
        id: String,
        url: String,
    ) {
        replaceTab(id) { it.copy(url = url, title = null, error = null, isLoading = true, stopped = false) }
    }

    /**
     * A committed page; bumps [BrowserTab.navigationGeneration] (HXA-061/062 token binding).
     * The current System WebView also fires this for the built-in ERROR page right after a
     * main-frame failure — that finish commits nothing, so with an error already set the
     * callback is a no-op (the error page stays, the generation does not bump).
     */
    fun onPageFinished(
        id: String,
        url: String,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        val tab = requireTab(id)
        if (tab.error != null) return
        replaceTab(id) {
            it.copy(
                url = url,
                title = title,
                isLoading = false,
                error = null,
                stopped = false,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                navigationGeneration = it.navigationGeneration + 1,
            )
        }
    }

    /**
     * Main-frame failure. `null` from [BrowserErrorMapping.map] (both codes zero, or an
     * aborted load) only clears the loading state — no error page.
     */
    fun onMainFrameError(
        id: String,
        netError: Int,
        clientError: Int,
        failingUrl: String?,
    ) {
        val kind = BrowserErrorMapping.map(netError, clientError)
        if (kind == null) {
            // Zero codes / aborted load: clear the loading state (and the stop marker — this
            // callback is a real platform transition, not the user pressing stop).
            replaceTab(id) { it.copy(isLoading = false, stopped = false) }
            return
        }
        val rawCode = if (netError != 0) netError else clientError
        replaceTab(id) { it.copy(isLoading = false, error = LoadError(kind, failingUrl, rawCode), stopped = false) }
    }

    /**
     * Codeless main-frame failure (the modern `onReceivedError` callback carries no numeric
     * code): surfaces an UNKNOWN error page unless a typed [onMainFrameError] already set one,
     * or the tab was stopped by the user — the codeless callback cannot tell an aborted load
     * from a real failure, and [BrowserTab.stopped] is the only marker of the former. It must
     * NOT be gated on [BrowserTab.isLoading]: the legacy callback may clear it (with a
     * zero-code event) before the codeless one arrives for the SAME failure.
     */
    fun onMainFrameUnknownError(
        id: String,
        failingUrl: String?,
    ) {
        val tab = requireTab(id)
        if (tab.error != null || tab.stopped) return
        replaceTab(id) {
            it.copy(isLoading = false, error = LoadError(BrowserErrorKind.UNKNOWN, failingUrl, null), stopped = false)
        }
    }

    /** TLS failure: the host already cancelled the error handler (never proceeds). */
    fun onSslError(
        id: String,
        failingUrl: String,
    ) {
        replaceTab(id) { it.copy(isLoading = false, error = LoadError(BrowserErrorKind.SSL, failingUrl, null)) }
    }

    // ---------------------------------------------------------------- internals

    private fun requireTab(id: String): BrowserTab =
        state.tabs.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("unknown tab: $id")

    private fun replaceTab(
        id: String,
        transform: (BrowserTab) -> BrowserTab,
    ) {
        requireTab(id)
        state = state.copy(tabs = state.tabs.map { if (it.id == id) transform(it) else it })
    }

    companion object {
        const val ABOUT_BLANK = "about:blank"
        const val DEFAULT_MAX_TABS = 8
        const val HARD_MAX_TABS = 16

        fun newTabId(): String = "tab-" + UUID.randomUUID()

        private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        /**
         * User-typed input: a value that already carries a URI scheme (`scheme://...` or the
         * schemeless `about:` / `data:` forms) passes through untouched so the policy sees
         * it verbatim (a typed `file:///...` must reach the gate and be DENIED, not
         * rewritten); anything else is treated as an http(s) host and prefixed. Pure —
         * unit-testable on the JVM, which is where the rewrite bug would otherwise hide.
         */
        @Suppress("ReturnCount") // one early return per input shape (empty / scheme / schemeless / host)
        internal fun normalizeInput(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return trimmed
            if (SCHEME_PREFIX.find(trimmed) != null) return trimmed
            val lowered = trimmed.lowercase()
            if (lowered.startsWith("about:") || lowered.startsWith("data:")) return trimmed
            return "https://$trimmed"
        }
    }
}
