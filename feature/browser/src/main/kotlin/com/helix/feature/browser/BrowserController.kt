package com.helix.feature.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import com.helix.feature.browser.engine.AdBlockEngine
import com.helix.feature.browser.engine.SearchEngines
import com.helix.feature.browser.engine.UserScriptEngine
import com.helix.feature.browser.engine.WebPageTools
import com.helix.feature.browser.snapshot.BrowserOrigin
import com.helix.feature.browser.snapshot.BrowserSnapshot
import com.helix.feature.browser.snapshot.BrowserSnapshotScript
import com.helix.feature.browser.snapshot.LiveTabState
import com.helix.feature.browser.snapshot.SnapshotBinder
import com.helix.feature.browser.snapshot.SnapshotFailure
import com.helix.feature.browser.snapshot.SnapshotResult
import com.helix.feature.browser.snapshot.SnapshotToken
import com.helix.feature.browser.snapshot.TokenVerdict
import com.helix.feature.browser.storage.Bookmark
import com.helix.feature.browser.storage.BrowserPreferences
import com.helix.feature.browser.storage.BrowserStorage
import com.helix.feature.browser.storage.HistoryItem
import com.helix.feature.browser.storage.SpeedDial
import com.helix.feature.browser.storage.UserScript
import com.helix.feature.browser.webview.WebViewTabHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.URL

/**
 * State of Find in Page.
 */
data class FindInPageState(
    val query: String = "",
    val activeMatch: Int = 0,
    val totalMatches: Int = 0,
    val isSearching: Boolean = false,
)

/**
 * The browser feature's Android facade (HXA-060): owns the pure [BrowserTabController]
 * (tab state machine + the URL policy choke point) and download queue. Activity-owned
 * [BrowserViewOwner] instances hold the lazily-created [WebViewTabHost] resources. The Compose UI binds to [state] /
 * [downloads] and calls the command methods — it never touches WebView, DAOs or HTTP
 * (AGENTS.md).
 *
 * Threading: every public method must be called on the main thread (Compose composition
 * and WebView callbacks already are). The ONLY background work is streaming a
 * user-approved download onto the user-picked SAF document, on a single-thread executor;
 * it reports back through the thread-safe [downloads] [StateFlow].
 *
 * The feature facade: one command per user action + one callback per WebView event, so the
 * function count is intrinsic to the surface, not a design smell.
 */
@Suppress("TooManyFunctions")
class BrowserController(
    context: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext

    val storage = BrowserStorage(appContext)
    val adBlock = AdBlockEngine()
    val userScripts = UserScriptEngine(storage.loadUserScripts())

    private val tabs = BrowserTabController()
    private var ownerBinding = WeakReference<BrowserViewOwner>(null)
    private val hosts: Map<String, WebViewTabHost> get() = ownerBinding.get()?.hosts.orEmpty()

    /** The last successful snapshot per tab (HXA-061); the node tokens the HXA-062 tools may use. */
    private val snapshots = HashMap<String, BrowserSnapshot>()
    private val _state = MutableStateFlow(tabs.state())
    private val downloadQueue = BrowserDownloadQueue(appContext)

    /** Live tab state; the UI composes from this. */
    val state: StateFlow<BrowserTabController.State> = _state.asStateFlow()

    /** The download queue; newly requested items are appended at the end. */
    val downloads: StateFlow<List<DownloadItem>> = downloadQueue.downloads

    private val _preferences =
        MutableStateFlow(
            storage.loadPreferences().also {
                adBlock.enabled = it.adBlockEnabled
            },
        )
    val preferences: StateFlow<BrowserPreferences> = _preferences.asStateFlow()

    private val _bookmarks = MutableStateFlow(storage.loadBookmarks())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow(storage.loadHistory())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _speedDials = MutableStateFlow(storage.loadSpeedDials())
    val speedDials: StateFlow<List<SpeedDial>> = _speedDials.asStateFlow()

    private val _scripts = MutableStateFlow(storage.loadUserScripts())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    private val _findState = MutableStateFlow(FindInPageState())
    val findState: StateFlow<FindInPageState> = _findState.asStateFlow()

    private val _contextMenu = MutableStateFlow<ContextMenuData?>(null)
    val contextMenu: StateFlow<ContextMenuData?> = _contextMenu.asStateFlow()

    fun clearContextMenu() {
        _contextMenu.value = null
    }

    val adBlockedCount: StateFlow<Long> = adBlock.blockedCount

    // ---------------------------------------------------------------- tab commands

    fun tryNewTab(isIncognito: Boolean = false): String? {
        val id = tabs.tryNewTab(isIncognito) ?: return null
        hosts.values.forEach { it.cancelDialogs() }
        publish()
        return id
    }

    fun newTab(isIncognito: Boolean = false): String {
        val id = tabs.newTab(isIncognito)
        hosts.values.forEach { it.cancelDialogs() }
        publish()
        return id
    }

    fun closeAllTabs() {
        tabs.closeAllTabs()
        ownerBinding.get()?.clear()
        snapshots.clear()
        publish()
    }

    fun setDesktopMode(
        id: String,
        enabled: Boolean,
    ) {
        tabs.setDesktopMode(id, enabled)
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            host(id).setDesktopMode(enabled)
            host(id).reload()
        }
        publish()
    }

    fun closeTab(id: String) {
        tabs.closeTab(id)
        ownerBinding
            .get()
            ?.hosts
            ?.remove(id)
            ?.destroy()
        snapshots.remove(id)
        publish()
    }

    fun select(id: String) {
        hosts.values.forEach { it.cancelDialogs() }
        tabs.select(id)
        publish()
    }

    /**
     * Smart navigation: automatically classifies user input as a URL or a search query
     * and performs navigation.
     */
    fun smartNavigate(
        id: String,
        rawInput: String,
    ) {
        val currentEngine = SearchEngines.getById(_preferences.value.searchEngineId)
        val resolved = SearchEngines.resolveInput(rawInput, currentEngine)
        navigate(id, resolved)
    }

    /**
     * The user-facing navigation: input normalization, then the policy choke point. A
     * denial leaves the tab on its policy error page; the WebView is never asked to load
     * a denied URL (doc 09 §3.4).
     */
    fun navigate(
        id: String,
        rawUrl: String,
    ) {
        navigateOutcome(id, rawUrl)
    }

    /**
     * The policy-choke-point navigation, returning a [BrowserNavResult] for the HXA-062 tool
     * bridge. A denial leaves the tab on its policy error page and the WebView is never asked
     * to load the denied URL (doc 09 §3.4); the denial reason is read back from the tab's
     * [PolicyBlockedError].
     */
    @Suppress("ReturnCount")
    fun navigateOutcome(
        id: String,
        rawUrl: String,
    ): BrowserNavResult {
        if (!isLive(id)) return BrowserNavResult.NoTab
        if (ownerBinding.get()?.available != true) return BrowserNavResult.Denied("browser-host-unavailable")
        val command = tabs.navigate(id, BrowserTabController.normalizeInput(rawUrl))
        if (command !is BrowserTabController.TabCommand.Load) {
            publish()
            val reason =
                (
                    tabs
                        .state()
                        .tabs
                        .first { it.id == id }
                        .error as? PolicyBlockedError
                )?.reason?.code
            return BrowserNavResult.Denied(reason ?: "denied")
        }
        snapshots.remove(id)
        host(id).load(command.url)
        publish()
        return BrowserNavResult.Started(command.url, BrowserOrigin.of(command.url).orEmpty())
    }

    /**
     * Opens a new tab and, for an admitted [url], navigates it (HXA-062 `browser.open`). A blank
     * [url] yields a blank tab; a denied [url] leaves the fresh tab on its policy error page and
     * is reported as the blank document — the denied URL is never loaded.
     */
    fun openTab(url: String): BrowserOpenResult {
        if (url.isNotBlank() && ownerBinding.get()?.available != true) {
            return BrowserOpenResult("", "", "", "browser-host-unavailable")
        }
        val id = newTab()
        return when (val result = if (url.isBlank()) null else navigateOutcome(id, url)) {
            is BrowserNavResult.Started -> BrowserOpenResult(id, result.url, result.origin)
            else -> BrowserOpenResult(id, BrowserTabController.ABOUT_BLANK, BrowserOrigin.ABOUT_BLANK)
        }
    }

    fun goBack(id: String) {
        goBackOutcome(id)
    }

    @Suppress("ReturnCount")
    fun goBackOutcome(id: String): BrowserHistResult {
        if (!isLive(id)) return BrowserHistResult.NoTab
        if (hosts[id] == null) return BrowserHistResult.NoChange("page-requires-navigation")
        val command = tabs.goBack(id)
        if (command !is BrowserTabController.TabCommand.Back) {
            publish()
            return BrowserHistResult.NoChange("no earlier page to go back to")
        }
        snapshots.remove(id)
        host(id).goBack()
        publish()
        return BrowserHistResult.Moved
    }

    fun goForward(id: String) {
        goForwardOutcome(id)
    }

    @Suppress("ReturnCount")
    fun goForwardOutcome(id: String): BrowserHistResult {
        if (!isLive(id)) return BrowserHistResult.NoTab
        if (hosts[id] == null) return BrowserHistResult.NoChange("page-requires-navigation")
        val command = tabs.goForward(id)
        if (command !is BrowserTabController.TabCommand.Forward) {
            publish()
            return BrowserHistResult.NoChange("no later page to go forward to")
        }
        snapshots.remove(id)
        host(id).goForward()
        publish()
        return BrowserHistResult.Moved
    }

    fun reload(id: String) {
        reloadOutcome(id)
    }

    @Suppress("ReturnCount")
    fun reloadOutcome(id: String): BrowserReloadResult {
        if (!isLive(id)) return BrowserReloadResult.NoTab
        if (hosts[id] == null) return BrowserReloadResult.NoChange("page-requires-navigation")
        val command = tabs.reload(id)
        if (command !is BrowserTabController.TabCommand.Reload) {
            publish()
            return BrowserReloadResult.NoChange("nothing committed to reload")
        }
        snapshots.remove(id)
        host(id).reload()
        publish()
        return BrowserReloadResult.Reloaded
    }

    fun stop(id: String) {
        val command = tabs.stop(id)
        hosts[id]?.cancelDialogs()
        if (command is BrowserTabController.TabCommand.Stop) hosts[id]?.stop()
        publish()
    }

    /** Retry the tab's current URL after a load error. */
    fun retry(id: String) {
        val tab = tabs.state().tabs.firstOrNull { it.id == id } ?: return
        if (tab.error is LoadError) navigate(id, tab.url)
    }

    /**
     * The tab's WebView, or null while the tab has never navigated (its host is created
     * lazily on the first Load). The UI composes the view via AndroidView; releasing the
     * view in the UI never destroys the host — only [closeTab] / [clearHistory] do.
     */
    fun hostView(id: String): WebView? = hosts[id]?.webView

    // ---------------------------------------------------------------- snapshot（HXA-061，doc 09 §3.3/§3.4）

    /**
     * Runs the fixed versioned DOM-extraction script on the tab's committed page and
     * delivers a bounded [BrowserSnapshot] — or a fail-closed [SnapshotResult.Failed] — via
     * [onResult] on the main thread (doc 09 §3.3 `browser.snapshot`; §3.4: only Helix's own
     * versioned script fragment is ever evaluated, the model cannot submit a script).
     *
     * A tab without a settled page (still loading, on its error page, or never navigated so
     * it has no WebView) fails closed with [SnapshotFailure.NO_PAGE].
     */
    @Suppress("ComplexCondition")
    fun snapshot(
        id: String,
        onResult: (SnapshotResult) -> Unit,
    ) {
        val tab = tabs.state().tabs.firstOrNull { it.id == id }
        val host = hosts[id]
        if (tab == null || host == null || tab.isLoading || tab.error != null || tab.navigationGeneration < 1) {
            onResult(SnapshotResult.Failed(SnapshotFailure.NO_PAGE))
            return
        }
        host.evaluateFixed(BrowserSnapshotScript.EXTRACT) { raw ->
            // The tab may have closed / committed a newer page while the script ran: only
            // publish a result for a tab that still exists.
            if (!isLive(id)) return@evaluateFixed
            val liveTab = tabs.state().tabs.firstOrNull { it.id == id } ?: return@evaluateFixed
            val result = SnapshotBinder.bind(raw, liveTab, clockMillis())
            if (result is SnapshotResult.Success) snapshots[id] = result.snapshot else snapshots.remove(id)
            onResult(result)
        }
    }

    /** The last successful snapshot for [id], if any — the snapshot whose node tokens are live. */
    fun latestSnapshot(id: String): BrowserSnapshot? = snapshots[id]

    /**
     * The tab's live state, or null when unknown (HXA-062). Thread-safe: reads the published
     * [StateFlow] value, so the tool bridge may call it off the main thread.
     */
    fun tab(id: String): BrowserTab? = state.value.tabs.firstOrNull { it.id == id }

    /**
     * Runs a FIXED, versioned action script on the tab's settled page and delivers the raw
     * result via [onResult] on the main thread (HXA-062 `browser.click` / `type` / `scroll`;
     * doc 09 §3.4: [script] is always a
     * [com.helix.feature.browser.snapshot.BrowserActionScript] fragment — the model cannot
     * submit a script). A tab without a settled page fails closed with
     * [EvalFixedOutcome.NoPage]. Must be called on the main thread.
     */
    @Suppress("ComplexCondition")
    fun evaluateFixed(
        id: String,
        script: String,
        onResult: (EvalFixedOutcome) -> Unit,
    ) {
        val tab = tabs.state().tabs.firstOrNull { it.id == id }
        val host = hosts[id]
        if (tab == null || host == null || tab.isLoading || tab.error != null || tab.navigationGeneration < 1) {
            onResult(EvalFixedOutcome.NoPage)
            return
        }
        host.evaluateFixed(script) { raw ->
            if (!isLive(id)) return@evaluateFixed
            onResult(EvalFixedOutcome.Result(raw))
        }
    }

    /**
     * Captures the tab's WebView to PNG bytes (HXA-062 `browser.screenshot`; doc 09 §3.3:
     * screenshot only the Helix WebView, save to the Workspace). Returns null when the tab has
     * no settled, sized page. Must be called on the main thread (WebView draw).
     */
    @Suppress("ComplexCondition", "ReturnCount")
    fun capturePagePng(id: String): ByteArray? {
        val tab = tabs.state().tabs.firstOrNull { it.id == id }
        val host = hosts[id]
        if (tab == null || host == null || tab.isLoading || tab.error != null || tab.navigationGeneration < 1) {
            return null
        }
        val view = host.webView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = createBitmap(width, height)
        try {
            view.draw(Canvas(bitmap))
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            return buffer.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Validates a node token against the tab's LIVE state (doc 09 §3.3: 导航、刷新、DOM 大变化
     * 或超时都会使 token 失效). The host is the sole minter and validator — a token is usable
     * only while its tab, origin, navigation generation, snapshot fingerprint and TTL all
     * still match the live browser; any drift is a fail-closed [TokenVerdict].
     */
    @Suppress("ReturnCount")
    fun verifyNodeToken(
        id: String,
        token: String,
        nowMillis: Long = clockMillis(),
    ): TokenVerdict {
        val parsed = SnapshotToken.parse(token) ?: return TokenVerdict.MalformedToken
        val tab = tabs.state().tabs.firstOrNull { it.id == id } ?: return TokenVerdict.WrongTab
        val origin = BrowserOrigin.of(tab.url) ?: return TokenVerdict.WrongTab
        val live =
            LiveTabState(
                tabId = tab.id,
                origin = origin,
                navigationGeneration = tab.navigationGeneration,
                lastSnapshotFingerprint = snapshots[id]?.fingerprint,
            )
        return SnapshotToken.validate(parsed, live, nowMillis)
    }

    // ---------------------------------------------------------------- downloads

    fun requestDownload(request: DownloadRequest) = downloadQueue.requestDownload(request)

    fun saveDownload(
        itemId: String,
        documentUri: Uri,
    ) = downloadQueue.saveDownload(itemId, documentUri)

    fun dismissDownload(itemId: String) = downloadQueue.dismissDownload(itemId)

    // ---------------------------------------------------------------- 独立的清除入口（doc 09 §3.4）

    fun clearCookies() {
        val manager = CookieManager.getInstance()
        manager.flush()
        manager.removeAllCookies(null)
        manager.flush()
    }

    fun clearCache() {
        WebStorage.getInstance().deleteAllData()
        hosts.values.forEach { it.clearCache() }
    }

    /** Per-tab history lives inside each WebView: drop every host and start from one blank tab. */
    fun clearHistory() {
        ownerBinding.get()?.clear()
        snapshots.clear()
        while (tabs.state().tabs.isNotEmpty()) {
            tabs.closeTab(
                tabs
                    .state()
                    .tabs
                    .last()
                    .id,
            )
        }
        tabs.newTab()
        clearBrowsingHistory()
        publish()
    }

    // ---------------------------------------------------------------- Find in Page

    fun findInPage(
        id: String,
        query: String,
    ) {
        _findState.value = _findState.value.copy(query = query, isSearching = true)
        if (query.isBlank()) {
            if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
                host(id).clearFindMatches()
            }
            _findState.value = _findState.value.copy(activeMatch = 0, totalMatches = 0)
            return
        }
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            host(id).findAllAsync(query) { active, total ->
                _findState.value = _findState.value.copy(activeMatch = active, totalMatches = total)
            }
        }
    }

    fun findNext(
        id: String,
        forward: Boolean,
    ) {
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            host(id).findNext(forward)
        }
    }

    fun closeFindInPage(id: String) {
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            host(id).clearFindMatches()
        }
        _findState.value = FindInPageState()
    }

    // ---------------------------------------------------------------- Bookmarks & History & Speed Dials

    fun addBookmark(
        title: String,
        url: String,
    ) {
        if (url.isBlank() || url == BrowserTabController.ABOUT_BLANK) return
        val current = _bookmarks.value
        if (current.any { it.url == url }) return
        val updated = current + Bookmark(title = title.ifBlank { url }, url = url)
        _bookmarks.value = updated
        storage.saveBookmarks(updated)
    }

    fun removeBookmark(id: String) {
        val updated = _bookmarks.value.filterNot { it.id == id }
        _bookmarks.value = updated
        storage.saveBookmarks(updated)
    }

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    fun addHistory(
        title: String,
        url: String,
    ) {
        val current = _history.value.toMutableList()
        current.removeAll { it.url == url }
        current.add(0, HistoryItem(title = title.ifBlank { url }, url = url))
        val trimmed = if (current.size > 500) current.take(500) else current
        _history.value = trimmed
        storage.saveHistory(trimmed)
    }

    fun removeHistory(id: String) {
        val updated = _history.value.filterNot { it.id == id }
        _history.value = updated
        storage.saveHistory(updated)
    }

    fun clearBrowsingHistory() {
        _history.value = emptyList()
        storage.saveHistory(emptyList())
    }

    fun addSpeedDial(
        title: String,
        url: String,
    ) {
        val iconText = title.take(2).uppercase().ifBlank { "W" }
        val updated = _speedDials.value + SpeedDial(title = title, url = url, iconText = iconText)
        _speedDials.value = updated
        storage.saveSpeedDials(updated)
    }

    fun removeSpeedDial(id: String) {
        val updated = _speedDials.value.filterNot { it.id == id }
        _speedDials.value = updated
        storage.saveSpeedDials(updated)
    }

    // ---------------------------------------------------------------- User Scripts

    fun saveUserScript(script: UserScript) {
        val current = _scripts.value.toMutableList()
        val index = current.indexOfFirst { it.id == script.id }
        if (index >= 0) {
            current[index] = script
        } else {
            current.add(script)
        }
        _scripts.value = current
        storage.saveUserScripts(current)
        userScripts.updateScripts(current)
    }

    fun deleteUserScript(id: String) {
        val updated = _scripts.value.filterNot { it.id == id }
        _scripts.value = updated
        storage.saveUserScripts(updated)
        userScripts.updateScripts(updated)
    }

    fun toggleUserScript(id: String) {
        val updated =
            _scripts.value.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
        _scripts.value = updated
        storage.saveUserScripts(updated)
        userScripts.updateScripts(updated)
    }

    // ---------------------------------------------------------------- Preferences & Tools

    fun setSearchEngine(id: String) {
        val updated = _preferences.value.copy(searchEngineId = id)
        _preferences.value = updated
        storage.savePreferences(updated)
    }

    fun toggleAdBlock(): Boolean {
        val newEnabled = !_preferences.value.adBlockEnabled
        val updated = _preferences.value.copy(adBlockEnabled = newEnabled)
        _preferences.value = updated
        adBlock.enabled = newEnabled
        storage.savePreferences(updated)
        return newEnabled
    }

    fun toggleNoImageMode(): Boolean {
        val newEnabled = !_preferences.value.noImageMode
        val updated = _preferences.value.copy(noImageMode = newEnabled)
        _preferences.value = updated
        hosts.values.forEach { it.setNoImageMode(newEnabled) }
        storage.savePreferences(updated)
        return newEnabled
    }

    fun toggleNightMode(): Boolean {
        val newEnabled = !_preferences.value.nightMode
        val updated = _preferences.value.copy(nightMode = newEnabled)
        _preferences.value = updated
        hosts.values.forEach { it.setNightMode(newEnabled) }
        storage.savePreferences(updated)
        return newEnabled
    }

    fun injectEruda(id: String) {
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            WebPageTools.injectEruda(host(id).webView)
        }
    }

    fun extractSource(
        id: String,
        onResult: (String) -> Unit,
    ) {
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            WebPageTools.extractSource(host(id).webView, onResult)
        } else {
            onResult("")
        }
    }

    fun extractReader(
        id: String,
        onResult: (title: String, content: String) -> Unit,
    ) {
        if (ownerBinding.get()?.hosts?.containsKey(id) == true) {
            WebPageTools.extractReaderContent(host(id).webView, onResult)
        } else {
            onResult("", "")
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Bind before UI/tool use; replacement invalidates old callbacks before releasing Views. */
    fun attach(owner: BrowserViewOwner) {
        check(owner.available) { "browser owner is destroyed" }
        val previous = ownerBinding.get()
        if (previous === owner) return
        ownerBinding = WeakReference(owner)
        previous?.destroy()
        invalidatePages()
    }

    /** A late callback from an old Activity cannot detach the new Activity's owner. */
    fun detach(owner: BrowserViewOwner) {
        if (ownerBinding.get() !== owner) {
            owner.destroy()
            return
        }
        ownerBinding.clear()
        owner.destroy()
        invalidatePages()
    }

    /** Best-effort per-view pause; it does not globally suspend JavaScript timers. */
    fun pause(owner: BrowserViewOwner? = ownerBinding.get()) {
        if (owner === ownerBinding.get()) owner?.pause()
    }

    fun resume(owner: BrowserViewOwner? = ownerBinding.get()) {
        if (owner === ownerBinding.get()) owner?.resume()
    }

    /** Clear page resources while keeping the current live Activity binding usable. */
    fun destroy() {
        ownerBinding.get()?.clear()
        invalidatePages()
    }

    private fun invalidatePages() {
        snapshots.clear()
        tabs.releasePages()
        publish()
    }

    // ---------------------------------------------------------------- internals

    private fun host(id: String): WebViewTabHost {
        val owner = checkNotNull(ownerBinding.get()) { "browser-host-unavailable" }
        return owner.hosts.getOrPut(id) {
            lateinit var created: WebViewTabHost

            fun live(): Boolean = ownerBinding.get() === owner && owner.hosts[id] === created && isLive(id)
            created =
                owner.create(
                    object : BrowserTabListener {
                        override fun onPageStarted(url: String) {
                            if (!live()) return
                            tabs.onPageStarted(id, url)
                            publish()
                        }

                        override fun onProgressChanged(progress: Int) {
                            if (!live()) return
                            tabs.onProgressChanged(id, progress)
                            publish()
                        }

                        override fun onPageFinished(
                            url: String,
                            title: String?,
                            canGoBack: Boolean,
                            canGoForward: Boolean,
                        ) {
                            if (!live()) return
                            tabs.onPageFinished(id, url, title, canGoBack, canGoForward)
                            val tab = tabs.state().tabs.firstOrNull { it.id == id }
                            val shouldRecord =
                                tab?.isIncognito == false &&
                                    url.isNotBlank() &&
                                    url != BrowserTabController.ABOUT_BLANK
                            if (shouldRecord) {
                                addHistory(title ?: url, url)
                            }
                            publish()
                        }

                        override fun onMainFrameError(
                            netError: Int,
                            clientError: Int,
                            failingUrl: String?,
                        ) {
                            if (!live()) return
                            tabs.onMainFrameError(id, netError, clientError, failingUrl)
                            publish()
                        }

                        override fun onMainFrameUnknownError(failingUrl: String?) {
                            if (!live()) return
                            tabs.onMainFrameUnknownError(id, failingUrl)
                            publish()
                        }

                        override fun onRendererGone(failingUrl: String?) {
                            if (!live()) return
                            owner.hosts.remove(id)
                            snapshots.remove(id)
                            tabs.onMainFrameError(id, 0, android.webkit.WebViewClient.ERROR_UNKNOWN, failingUrl)
                            publish()
                        }

                        override fun onSslError(failingUrl: String) {
                            if (!live()) return
                            tabs.onSslError(id, failingUrl)
                            publish()
                        }

                        override fun onNavigationAttempt(url: String) {
                            if (!live()) return
                            // The re-admission path: page-initiated navigations are admitted by the
                            // SAME choke point as user-typed URLs.
                            val command = tabs.navigate(id, BrowserTabController.normalizeInput(url))
                            if (command is BrowserTabController.TabCommand.Load) host(id).load(command.url)
                            publish()
                        }

                        override fun onDownloadRequest(request: DownloadRequest) {
                            if (!live()) return
                            requestDownload(request)
                        }

                        override fun onContextMenu(contextMenu: ContextMenuData) {
                            if (!live()) return
                            _contextMenu.value = contextMenu
                        }
                    },
                    canShowDialogs = { live() && owner.resumed && owner.available && state.value.selectedId == id },
                )
            created.adBlockEngine = adBlock
            created.userScriptEngine = userScripts
            created.isNightMode = { _preferences.value.nightMode }
            created.setNoImageMode(_preferences.value.noImageMode)
            if (!owner.resumed) created.pause()
            created
        }
    }

    private fun isLive(id: String): Boolean = tabs.state().tabs.any { it.id == id }

    private fun publish() {
        _state.value = tabs.state()
    }
}
