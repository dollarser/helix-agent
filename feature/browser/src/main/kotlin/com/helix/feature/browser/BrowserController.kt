package com.helix.feature.browser

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.helix.feature.browser.webview.WebViewTabHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val appContext = context.applicationContext

    private val tabs = BrowserTabController()
    private val hosts = HashMap<String, WebViewTabHost>()
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
        // A denial leaves the tab on its policy error page; the WebView is never asked to
        // load a denied URL (doc 09 §3.4).
        val command = tabs.navigate(id, BrowserTabController.normalizeInput(rawUrl))
        if (command is BrowserTabController.TabCommand.Load) host(id).load(command.url)
        publish()
    }

    fun goBack(id: String) {
        val command = tabs.goBack(id)
        if (command is BrowserTabController.TabCommand.Back) host(id).goBack()
        publish()
    }

    fun goForward(id: String) {
        val command = tabs.goForward(id)
        if (command is BrowserTabController.TabCommand.Forward) host(id).goForward()
        publish()
    }

    fun reload(id: String) {
        val command = tabs.reload(id)
        if (command is BrowserTabController.TabCommand.Reload) host(id).reload()
        publish()
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
