package com.helix.feature.browser

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
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
import com.helix.feature.browser.storage.BrowserDataPersistence
import com.helix.feature.browser.storage.BrowserPreferences
import com.helix.feature.browser.storage.BrowserSessionPersistence
import com.helix.feature.browser.storage.BrowserStorage
import com.helix.feature.browser.storage.HistoryItem
import com.helix.feature.browser.storage.SpeedDial
import com.helix.feature.browser.storage.UserScript
import com.helix.feature.browser.webview.WebViewTabHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
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
 * and WebView callbacks already are). Browser metadata uses ordered background I/O;
 * user-approved downloads stream onto user-picked SAF documents on their executor and
 * report back through the thread-safe [downloads] [StateFlow].
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
    val engineState = BrowserEngineStartup.state
    val adBlock = AdBlockEngine()
    val userScripts = UserScriptEngine(emptyList())

    private val tabs = BrowserTabController()
    private val viewBinding = BrowserViewBinding { invalidatePages() }
    private val hosts: Map<String, WebViewTabHost> get() = viewBinding.current?.hosts.orEmpty()

    /** The last successful snapshot per tab (HXA-061); the node tokens the HXA-062 tools may use. */
    private val snapshots = HashMap<String, BrowserSnapshot>()
    private val _state = MutableStateFlow(tabs.state())
    private val _storageFailure = MutableStateFlow(false)
    val storageFailure: StateFlow<Boolean> = _storageFailure.asStateFlow()
    private val sessions =
        BrowserSessionPersistence(
            File(appContext.filesDir, "browser/session.json"),
            restored = { saved ->
                tabs.restoreSession(saved)
                _state.value = tabs.state()
            },
            failed = { _storageFailure.value = true },
        )
    val sessionReady = sessions.ready
    private val downloadQueue = BrowserDownloadQueue(appContext)

    /** Live tab state; the UI composes from this. */
    val state: StateFlow<BrowserTabController.State> = _state.asStateFlow()

    /** Read-only host fact; observing it never creates a WebView or attaches an owner. */
    fun hostAvailable(): Boolean = viewBinding.current?.available == true

    /** The download queue; newly requested items are appended at the end. */
    val downloads: StateFlow<List<DownloadItem>> = downloadQueue.downloads

    private val _preferences = MutableStateFlow(BrowserPreferences())
    val preferences: StateFlow<BrowserPreferences> = _preferences.asStateFlow()

    private val _bookmarks = MutableStateFlow(emptyList<Bookmark>())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow(emptyList<HistoryItem>())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _speedDials = MutableStateFlow(emptyList<SpeedDial>())
    val speedDials: StateFlow<List<SpeedDial>> = _speedDials.asStateFlow()

    private val _scripts = MutableStateFlow(emptyList<UserScript>())
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

    fun tryNewTab(
        isIncognito: Boolean = false,
        originSessionId: String? = null,
    ): String? {
        val previous = tabs.state().selectedId
        val id =
            if (sessionReady.isCompleted &&
                !state.value.isClosed
            ) {
                tabs.tryNewTab(isIncognito, originSessionId)
            } else {
                null
            }
        if (id == null) return null
        if (tabs.state().selectedId != previous) hosts.values.forEach { it.cancelDialogs() }
        publish()
        return id
    }

    fun newTab(isIncognito: Boolean = false): String {
        check(sessionReady.isCompleted && !state.value.isClosed) { "browser is not open" }
        val id = tabs.newTab(isIncognito)
        hosts.values.forEach { it.cancelDialogs() }
        publish()
        return id
    }

    fun closeAllTabs() {
        tabs.closeAllTabs()
        viewBinding.current?.clear()
        snapshots.clear()
        publish()
    }

    fun setDesktopMode(
        id: String,
        enabled: Boolean,
    ) {
        tabs.setDesktopMode(id, enabled)
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            host(id).setDesktopMode(enabled)
            host(id).reload()
        }
        publish()
    }

    fun closeTab(id: String) {
        tabs.closeTab(id)
        viewBinding.current
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

    fun enterBrowser() {
        check(sessionReady.isCompleted)
        tabs.openBrowser()
        publish()
        warmUpEngine()
    }

    fun warmUpEngine(retry: Boolean = false) = BrowserEngineStartup.start(appContext, retry)

    fun showHome() {
        hosts.values.forEach { it.cancelDialogs() }
        tabs.showHome()
        publish()
        warmUpEngine()
    }

    fun showSelectedPage() {
        tabs.showSelectedPage()
        publish()
    }

    /** Stop page work immediately; wait for the ordered address checkpoint before reporting saved. */
    suspend fun closeBrowser(): Boolean {
        sessionReady.await()
        viewBinding.current?.clear()
        snapshots.clear()
        _contextMenu.value = null
        _findState.value = FindInPageState()
        tabs.closeBrowser()
        publish()
        return flushSession()
    }

    suspend fun flushSession(): Boolean {
        sessionReady.await()
        return sessions.flush(tabs.state())
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
        if (state.value.isClosed) return BrowserNavResult.Denied("browser-closed")
        if (engineState.value == BrowserEngineState.FAILED) return BrowserNavResult.Denied("browser-engine-unavailable")
        if (viewBinding.current?.available != true) return BrowserNavResult.Denied("browser-host-unavailable")
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
    fun openTab(
        url: String,
        originSessionId: String? = null,
    ): BrowserOpenResult {
        val unavailable =
            when {
                !sessionReady.isCompleted -> "browser-restoring"
                state.value.isClosed -> "browser-closed"
                engineState.value == BrowserEngineState.FAILED -> "browser-engine-unavailable"
                url.isNotBlank() && viewBinding.current?.available != true -> "browser-host-unavailable"
                else -> null
            }
        if (unavailable != null) return BrowserOpenResult("", "", "", unavailable)
        val id = tryNewTab(originSessionId = originSessionId)
        return if (id ==
            null
        ) {
            BrowserOpenResult("", "", "", "tab-limit-reached")
        } else {
            when (val result = if (url.isBlank()) null else navigateOutcome(id, url)) {
                is BrowserNavResult.Started -> BrowserOpenResult(id, result.url, result.origin)
                else -> BrowserOpenResult(id, BrowserTabController.ABOUT_BLANK, BrowserOrigin.ABOUT_BLANK)
            }
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
     * view in the UI keeps the host. Explicit close, owner retirement and memory reclaim
     * release it; metadata alone never recreates a page.
     */
    fun hostView(id: String): WebView? = hosts[id]?.webView

    // ---------------------------------------------------------------- snapshot（HXA-061，doc 09 §3.3/§3.4）

    /**
     * Runs the fixed versioned DOM-extraction script on the tab's committed page and
     * delivers a bounded [BrowserSnapshot] — or a fail-closed [SnapshotResult.Failed] — via
     * [onResult] on the main thread (doc 09 §3.3 `browser.snapshot`; §3.4: only Helix's own
     * versioned script fragment is ever evaluated, the model cannot submit a script).
     *
     * Loading, failed documents and unavailable hosts remain distinct failures; a refused
     * page-initiated app link does not make the existing document unavailable.
     */
    @Suppress("ComplexCondition")
    fun snapshot(
        id: String,
        stillRequested: () -> Boolean = { true },
        onResult: (SnapshotResult) -> Unit,
    ) {
        val tab = tabs.state().tabs.firstOrNull { it.id == id }
        val host = hosts[id]
        val unavailable = BrowserObservationFacts.snapshotFailure(tab, host != null)
        if (unavailable != null || tab == null || host == null) {
            onResult(SnapshotResult.Failed(unavailable ?: SnapshotFailure.NO_PAGE))
            return
        }
        val owner = viewBinding.current
        host.evaluateFixed(BrowserSnapshotScript.EXTRACT) { raw ->
            val liveTab = tab(id)
            if (!stillRequested() || viewBinding.current !== owner || hosts[id] !== host ||
                liveTab == null || liveTab.navigationGeneration != tab.navigationGeneration || liveTab.isLoading ||
                liveTab.error != null
            ) {
                onResult(SnapshotResult.Failed(SnapshotFailure.PAGE_CHANGED))
                return@evaluateFixed
            }
            val result = SnapshotBinder.bind(raw, liveTab, clockMillis())
            if (result is SnapshotResult.Success) snapshots[id] = result.snapshot else snapshots.remove(id)
            onResult(result)
        }
    }

    /** The last successful snapshot for [id], if any — the snapshot whose node tokens are live. */
    fun latestSnapshot(id: String): BrowserSnapshot? = snapshots[id]

    internal fun observationBinding(id: String): BrowserObservationBinding? =
        BrowserObservationBinding.capture(tab(id), viewBinding.current, hosts[id])

    internal fun observationState(binding: BrowserObservationBinding): com.helix.tools.browser.BrowserWaitStatus? =
        binding.state(viewBinding.current, hosts[binding.tabId], tab(binding.tabId))

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
        val owner = viewBinding.current
        host.evaluateFixed(script) { raw ->
            val liveTab = tab(id)
            val current =
                viewBinding.current === owner && hosts[id] === host &&
                    liveTab?.navigationGeneration == tab.navigationGeneration && !liveTab.isLoading
            // Dispatch already occurred. A lost page cannot prove the action had no effect.
            onResult(EvalFixedOutcome.Result(if (current) raw else null))
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
        return WebPageTools.capturePng(host.webView)
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
        manager.removeAllCookies { manager.flush() }
    }

    fun clearCache() {
        viewBinding.current?.takeIf { it.available }?.clearCache()
    }

    /** Per-tab history lives inside each WebView: drop every host and start from one blank tab. */
    fun clearHistory() {
        check(sessionReady.isCompleted)
        sessions.allowExplicitReset()
        viewBinding.current?.clear()
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
            if (viewBinding.current?.hosts?.containsKey(id) == true) {
                host(id).clearFindMatches()
            }
            _findState.value = _findState.value.copy(activeMatch = 0, totalMatches = 0)
            return
        }
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            host(id).findAllAsync(query) { active, total ->
                _findState.value = _findState.value.copy(activeMatch = active, totalMatches = total)
            }
        }
    }

    fun findNext(
        id: String,
        forward: Boolean,
    ) {
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            host(id).findNext(forward)
        }
    }

    fun closeFindInPage(id: String) {
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            host(id).clearFindMatches()
        }
        _findState.value = FindInPageState()
    }

    // ---------------------------------------------------------------- Bookmarks & History & Speed Dials

    private val persistence =
        BrowserDataPersistence(failed = { _storageFailure.value = true }) {
            _preferences.value = withContext(Dispatchers.IO) { storage.loadPreferences() }
            _bookmarks.value = withContext(Dispatchers.IO) { storage.loadBookmarks() }
            _history.value = withContext(Dispatchers.IO) { storage.loadHistory() }
            _speedDials.value = withContext(Dispatchers.IO) { storage.loadSpeedDials() }
            _scripts.value = withContext(Dispatchers.IO) { storage.loadUserScripts() }
            adBlock.enabled = _preferences.value.adBlockEnabled
            userScripts.updateScripts(_scripts.value)
            hosts.values.forEach { it.setNoImageMode(_preferences.value.noImageMode) }
        }

    fun dismissStorageFailure() {
        _storageFailure.value = false
    }

    private fun <T> editStored(
        state: MutableStateFlow<T>,
        save: (T) -> Unit,
        update: (T) -> T,
        after: (T) -> Unit = {},
    ) = persistence.edit(state, save, update, after)

    fun addBookmark(
        title: String,
        url: String,
    ) {
        if (url.isBlank() || url == BrowserTabController.ABOUT_BLANK) return
        editStored(_bookmarks, storage::saveBookmarks, { current ->
            if (current.any { it.url == url }) {
                current
            } else {
                current + Bookmark(title = title.ifBlank { url }.take(2048), url = url)
            }
        })
    }

    fun removeBookmark(id: String) =
        editStored(_bookmarks, storage::saveBookmarks, {
            it.filterNot { row -> row.id == id }
        })

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    fun addHistory(
        title: String,
        url: String,
    ) = editStored(_history, storage::saveHistory, {
        (
            listOf(HistoryItem(title = title.ifBlank { url }.take(2048), url = url)) +
                it.filterNot { row -> row.url == url }
        ).take(500)
    })

    fun removeHistory(id: String) = editStored(_history, storage::saveHistory, { it.filterNot { row -> row.id == id } })

    fun clearBrowsingHistory() = editStored(_history, storage::saveHistory, { emptyList() })

    fun addSpeedDial(
        title: String,
        url: String,
    ) = editStored(_speedDials, storage::saveSpeedDials, {
        it + SpeedDial(title = title.take(2048), url = url, iconText = title.take(2).uppercase().ifBlank { "W" })
    })

    fun removeSpeedDial(id: String) =
        editStored(_speedDials, storage::saveSpeedDials, {
            it.filterNot { row ->
                row.id ==
                    id
            }
        })

    fun saveUserScript(script: UserScript) =
        editStored(_scripts, storage::saveUserScripts, {
            require(script.code.toByteArray().size <= 256 * 1024)
            it.filterNot { row -> row.id == script.id } + script
        }, userScripts::updateScripts)

    fun deleteUserScript(id: String) =
        editStored(
            _scripts,
            storage::saveUserScripts,
            { it.filterNot { row -> row.id == id } },
            userScripts::updateScripts,
        )

    fun toggleUserScript(id: String) =
        editStored(
            _scripts,
            storage::saveUserScripts,
            { it.map { row -> if (row.id == id) row.copy(enabled = !row.enabled) else row } },
            userScripts::updateScripts,
        )

    fun setSearchEngine(id: String) =
        editStored(_preferences, storage::savePreferences, { it.copy(searchEngineId = id) })

    fun toggleAdBlock() =
        editStored(
            _preferences,
            storage::savePreferences,
            { it.copy(adBlockEnabled = !it.adBlockEnabled) },
            { adBlock.enabled = it.adBlockEnabled },
        )

    fun toggleNoImageMode() =
        editStored(
            _preferences,
            storage::savePreferences,
            { it.copy(noImageMode = !it.noImageMode) },
            { prefs -> hosts.values.forEach { it.setNoImageMode(prefs.noImageMode) } },
        )

    fun toggleNightMode() =
        editStored(
            _preferences,
            storage::savePreferences,
            { it.copy(nightMode = !it.nightMode) },
            { prefs -> hosts.values.forEach { it.setNightMode(prefs.nightMode) } },
        )

    fun injectEruda(id: String) {
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            WebPageTools.injectEruda(host(id).webView)
        }
    }

    fun extractSource(
        id: String,
        onResult: (String) -> Unit,
    ) {
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            WebPageTools.extractSource(host(id).webView, onResult)
        } else {
            onResult("")
        }
    }

    fun extractReader(
        id: String,
        onResult: (title: String, content: String) -> Unit,
    ) {
        if (viewBinding.current?.hosts?.containsKey(id) == true) {
            WebPageTools.extractReaderContent(host(id).webView, onResult)
        } else {
            onResult("", "")
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Bind before UI/tool use; replacement invalidates callbacks before releasing Views. */
    fun attach(owner: BrowserViewOwner) = viewBinding.attach(owner)

    fun detach(owner: BrowserViewOwner) = viewBinding.detach(owner)

    fun pause(owner: BrowserViewOwner? = viewBinding.current) = viewBinding.pause(owner)

    fun resume(owner: BrowserViewOwner? = viewBinding.current) = viewBinding.resume(owner)

    fun destroy() = viewBinding.clear()

    /** Ordinary UI_HIDDEN keeps pages; actual memory reclaim retires the current Views. */
    fun trimMemory(level: Int) = viewBinding.trimMemory(level)

    private fun invalidatePages() {
        snapshots.clear()
        tabs.releasePages()
        publish()
    }

    // ---------------------------------------------------------------- internals

    private val pageCallbacks =
        BrowserPageCallbacks(
            tabs,
            changed = ::publish,
            recordHistory = ::addHistory,
            invalidateSnapshot = { snapshots.remove(it) },
            download = ::requestDownload,
            contextMenu = { _contextMenu.value = it },
        )

    private fun host(id: String): WebViewTabHost {
        val owner = checkNotNull(viewBinding.current) { "browser-host-unavailable" }
        return owner.hosts.getOrPut(id) {
            lateinit var created: WebViewTabHost

            fun live(): Boolean = viewBinding.current === owner && owner.hosts[id] === created && isLive(id)
            created =
                owner.create(
                    pageCallbacks.listener(id, owner, ::live),
                    canShowDialogs = {
                        live() && owner.resumed && owner.available && state.value.selectedId == id &&
                            !state.value.isHomeVisible && !state.value.isClosed && tab(id)?.error == null
                    },
                )
            created.adBlockEngine = adBlock
            created.userScriptEngine = userScripts
            created.isNightMode = { _preferences.value.nightMode }
            created.setNoImageMode(_preferences.value.noImageMode)
            created.setDesktopMode(
                tabs
                    .state()
                    .tabs
                    .first { it.id == id }
                    .isDesktopMode,
            )
            if (!owner.resumed) created.pause()
            created
        }
    }

    private fun isLive(id: String): Boolean = tabs.state().tabs.any { it.id == id }

    private fun publish() {
        _state.value = tabs.state()
        sessions.update(tabs.state())
    }
}
