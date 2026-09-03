package com.helix.feature.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.helix.feature.browser.snapshot.BrowserOrigin
import com.helix.feature.browser.snapshot.BrowserSnapshot
import com.helix.feature.browser.snapshot.BrowserSnapshotScript
import com.helix.feature.browser.snapshot.LiveTabState
import com.helix.feature.browser.snapshot.SnapshotBinder
import com.helix.feature.browser.snapshot.SnapshotFailure
import com.helix.feature.browser.snapshot.SnapshotResult
import com.helix.feature.browser.snapshot.SnapshotToken
import com.helix.feature.browser.snapshot.TokenVerdict
import com.helix.feature.browser.webview.WebViewTabHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The browser feature's Android facade (HXA-060): owns the pure [BrowserTabController]
 * (tab state machine + the URL policy choke point), one lazily-created [WebViewTabHost]
 * per tab that navigated, and the download queue. The Compose UI binds to [state] /
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

    private val tabs = BrowserTabController()
    private val hosts = HashMap<String, WebViewTabHost>()

    /** The last successful snapshot per tab (HXA-061); the node tokens the HXA-062 tools may use. */
    private val snapshots = HashMap<String, BrowserSnapshot>()
    private val _state = MutableStateFlow(tabs.state())
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())

    private val downloadExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "helix-browser-download").apply { isDaemon = true }
        }

    /** Live tab state; the UI composes from this. */
    val state: StateFlow<BrowserTabController.State> = _state.asStateFlow()

    /** The download queue; newly requested items are appended at the end. */
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    // ---------------------------------------------------------------- tab commands

    fun newTab(): String {
        val id = tabs.newTab()
        publish()
        return id
    }

    fun closeTab(id: String) {
        tabs.closeTab(id)
        hosts.remove(id)?.destroy()
        snapshots.remove(id)
        publish()
    }

    fun select(id: String) {
        tabs.select(id)
        publish()
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
                )?.reason?.label
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
        val id = newTab()
        if (url.isBlank()) {
            return BrowserOpenResult(id, BrowserTabController.ABOUT_BLANK, BrowserOrigin.ABOUT_BLANK)
        }
        return when (val result = navigateOutcome(id, url)) {
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
        if (command is BrowserTabController.TabCommand.Stop) host(id).stop()
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
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

    /**
     * Entry point for the WebView download seam (and the on-device test): runs
     * [BrowserDownloadPolicy] and queues the result. A denial is queued as a visible,
     * DENIED row — no bytes are ever fetched for a denied item.
     */
    fun requestDownload(request: DownloadRequest) {
        when (val decision = BrowserDownloadPolicy.evaluate(request)) {
            is DownloadDecision.Save -> {
                _downloads.value =
                    _downloads.value +
                    DownloadItem(
                        id = newDownloadId(),
                        url = request.url,
                        fileName = decision.targetName,
                        declaredBytes = decision.declaredBytes,
                        status = DownloadStatus.PENDING_CHOICE,
                    )
            }

            is DownloadDecision.Denied -> {
                _downloads.value =
                    _downloads.value +
                    DownloadItem(
                        id = newDownloadId(),
                        url = request.url,
                        fileName =
                            BrowserDownloadPolicy.sanitizeName(request.suggestedName).ifBlank { "download" },
                        declaredBytes = if (request.contentLength > 0) request.contentLength else -1L,
                        status = DownloadStatus.DENIED,
                        denial = decision.reason,
                    )
            }
        }
    }

    /**
     * Streams the queued item into the user-picked SAF [documentUri]. The URL is
     * re-verified as http(s) at execution time (the queue is not a trust boundary,
     * doc 09 §3.4) and the same 100 MiB cap the policy uses is enforced byte-for-byte
     * when the declared length is unknown.
     *
     * One fail-closed return per guard (unknown item / not pending / non-http URL).
     */
    @Suppress("ReturnCount")
    fun saveDownload(
        itemId: String,
        documentUri: Uri,
    ) {
        val item = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (item.status != DownloadStatus.PENDING_CHOICE) return
        if (!BrowserDownloadPolicy.isHttpUrl(item.url)) {
            markDownload(itemId, DownloadStatus.DENIED, denial = DownloadDenial.URL)
            return
        }
        markDownload(itemId, DownloadStatus.SAVING)
        downloadExecutor.execute {
            val (status, detail) = streamToDocument(item, documentUri)
            // StateFlow is synchronized; this is the single sanctioned off-main-thread
            // write in this class.
            markDownload(itemId, status, detail = detail)
        }
    }

    /** Removes a finished / denied row; an in-flight SAVING row cannot be interrupted. */
    fun dismissDownload(itemId: String) {
        val item = _downloads.value.firstOrNull { it.id == itemId } ?: return
        if (item.status == DownloadStatus.SAVING) return
        _downloads.value = _downloads.value.filterNot { it.id == itemId }
    }

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
        hosts.values.forEach { it.destroy() }
        hosts.clear()
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
        publish()
    }

    // ---------------------------------------------------------------- lifecycle

    /** From the activity's onPause: stop JS timers / the compositor of every tab. */
    fun pause() {
        hosts.values.forEach { it.pause() }
    }

    /** From the activity's onResume. */
    fun resume() {
        hosts.values.forEach { it.resume() }
    }

    /** Tears down every WebView (activity onDestroy). */
    fun destroy() {
        hosts.values.forEach { it.destroy() }
        hosts.clear()
        snapshots.clear()
    }

    // ---------------------------------------------------------------- internals

    private fun host(id: String): WebViewTabHost =
        hosts.getOrPut(id) {
            WebViewTabHost(
                appContext,
                object : BrowserTabListener {
                    override fun onPageStarted(url: String) {
                        if (isLive(id)) tabs.onPageStarted(id, url)
                        publish()
                    }

                    override fun onPageFinished(
                        url: String,
                        title: String?,
                        canGoBack: Boolean,
                        canGoForward: Boolean,
                    ) {
                        if (isLive(id)) tabs.onPageFinished(id, url, title, canGoBack, canGoForward)
                        publish()
                    }

                    override fun onMainFrameError(
                        netError: Int,
                        clientError: Int,
                        failingUrl: String?,
                    ) {
                        if (isLive(id)) tabs.onMainFrameError(id, netError, clientError, failingUrl)
                        publish()
                    }

                    override fun onMainFrameUnknownError(failingUrl: String?) {
                        if (isLive(id)) tabs.onMainFrameUnknownError(id, failingUrl)
                        publish()
                    }

                    override fun onSslError(failingUrl: String) {
                        if (isLive(id)) tabs.onSslError(id, failingUrl)
                        publish()
                    }

                    override fun onNavigationAttempt(url: String) {
                        if (!isLive(id)) return
                        // The re-admission path: page-initiated navigations are admitted by the
                        // SAME choke point as user-typed URLs.
                        val command = tabs.navigate(id, BrowserTabController.normalizeInput(url))
                        if (command is BrowserTabController.TabCommand.Load) host(id).load(command.url)
                        publish()
                    }

                    override fun onDownloadRequest(request: DownloadRequest) {
                        requestDownload(request)
                    }
                },
            )
        }

    private fun isLive(id: String): Boolean = tabs.state().tabs.any { it.id == id }

    private fun publish() {
        _state.value = tabs.state()
    }

    /** One fail-closed return per guard (non-2xx response / unopenable document). */
    @Suppress("ReturnCount")
    private fun streamToDocument(
        item: DownloadItem,
        documentUri: Uri,
    ): Pair<DownloadStatus, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(item.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return DownloadStatus.FAILED to "HTTP $responseCode"
            val out =
                appContext.contentResolver.openOutputStream(documentUri)
                    ?: return DownloadStatus.FAILED to "无法打开所选位置"
            out.use { output -> copyWithCap(connection.inputStream, output) ?: DownloadStatus.SAVED to null }
        } catch (e: IOException) {
            DownloadStatus.FAILED to (e.message ?: "下载失败")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Copies [input] to [output] in 64 KiB chunks, enforcing the policy's 100 MiB cap
     * byte-for-byte (the declared length is not a trust boundary, doc 09 §3.4). A
     * non-null result is the cap-breach FAILED outcome; null means the copy completed.
     */
    private fun copyWithCap(
        input: InputStream,
        output: OutputStream,
    ): Pair<DownloadStatus, String?>? {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return null
            written += read
            if (written > BrowserDownloadPolicy.MAX_DOWNLOAD_BYTES) return DownloadStatus.FAILED to "超出 100 MiB 上限"
            output.write(buffer, 0, read)
        }
    }

    private fun markDownload(
        itemId: String,
        status: DownloadStatus,
        denial: DownloadDenial? = null,
        detail: String? = null,
    ) {
        _downloads.value =
            _downloads.value.map {
                if (it.id == itemId) {
                    it.copy(
                        status = status,
                        denial = denial ?: it.denial,
                        detail = detail ?: it.detail,
                    )
                } else {
                    it
                }
            }
    }

    private fun newDownloadId(): String = "dl-" + UUID.randomUUID()
}
