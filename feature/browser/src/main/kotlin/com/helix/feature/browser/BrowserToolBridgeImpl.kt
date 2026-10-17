package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.feature.browser.snapshot.BrowserActionScript
import com.helix.feature.browser.snapshot.BrowserOrigin
import com.helix.feature.browser.snapshot.SnapshotFailure
import com.helix.feature.browser.snapshot.SnapshotResult
import com.helix.feature.browser.snapshot.SnapshotToken
import com.helix.feature.browser.snapshot.TokenVerdict
import com.helix.tools.browser.ActionOutcome
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserObservationContext
import com.helix.tools.browser.BrowserTabView
import com.helix.tools.browser.BrowserTabsOutcome
import com.helix.tools.browser.BrowserToolBridge
import com.helix.tools.browser.BrowserWaitCondition
import com.helix.tools.browser.BrowserWaitOutcome
import com.helix.tools.browser.BrowserWaitRequest
import com.helix.tools.browser.BrowserWaitStatus
import com.helix.tools.browser.DownloadOutcome
import com.helix.tools.browser.FindOutcome
import com.helix.tools.browser.HistStatus
import com.helix.tools.browser.HistoryOutcome
import com.helix.tools.browser.NavStatus
import com.helix.tools.browser.NavigateOutcome
import com.helix.tools.browser.OpenOutcome
import com.helix.tools.browser.ReloadOutcome
import com.helix.tools.browser.ReloadStatus
import com.helix.tools.browser.ScreenshotOutcome
import com.helix.tools.browser.ScreenshotStatus
import com.helix.tools.browser.ScrollOutcome
import com.helix.tools.browser.ScrollStatus
import com.helix.tools.browser.SensitiveFieldClassifier
import com.helix.tools.browser.SnapshotOutcome
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The production [BrowserToolBridge] (HXA-062): executes the `browser.*` tools against the
 * main-thread [BrowserController].
 *
 * Threading: a tool executor runs on a background dispatcher, but [BrowserController] is
 * main-thread-only. This class bridges the two with a latch: [onMain] posts a block to the main
 * looper and blocks until it returns; [onMainAsync] posts a kickoff whose [completer] is called
 * (on the main thread) when the async WebView eval settles. The async deadline is deliberately
 * set ABOVE the controller's internal eval deadline, so a slow page surfaces as a settled
 * (null / NO_PAGE) result — a clean TIMED_OUT — long before the bridge's own latch gives up.
 *
 * Every method is fail-closed: an unknown tab is a NO_TAB outcome, a stale node token a
 * STALE_TOKEN outcome, a sensitive field a REFUSED outcome (the host's [SensitiveFieldClassifier]
 * is authoritative over the fixed action script — an action is PERFORMED only when BOTH agree
 * the field is normal), and an internal deadline a TIMED_OUT outcome.
 */
@Suppress("TooManyFunctions")
class BrowserToolBridgeImpl(
    private val controller: BrowserController,
    private val workspaceStore: WorkspaceArtifactStore,
    private val scopeId: String,
) : BrowserToolBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloader = BrowserDownloader(workspaceStore, scopeId)

    override fun open(
        url: String,
        sessionId: String?,
    ): OpenOutcome {
        val result = onMain(SYNC_TIMEOUT_MS) { controller.openTab(url, sessionId) }
        return OpenOutcome(result.tabId, result.url, result.origin, result.failureReason)
    }

    @Suppress("SwallowedException")
    override fun navigate(
        tabId: String,
        url: String,
    ): NavigateOutcome =
        try {
            when (val result = onMain(SYNC_TIMEOUT_MS) { controller.navigateOutcome(tabId, url) }) {
                is BrowserNavResult.Started -> NavigateOutcome(NavStatus.STARTED, result.url, result.origin, "")
                is BrowserNavResult.Denied -> NavigateOutcome(NavStatus.DENIED, "", "", result.reason)
                BrowserNavResult.NoTab -> NavigateOutcome(NavStatus.NO_TAB, "", "", "")
            }
        } catch (e: MainHopTimeout) {
            NavigateOutcome(NavStatus.TIMED_OUT, "", "", "")
        }

    override fun back(tabId: String): HistoryOutcome = history(tabId) { id -> controller.goBackOutcome(id) }

    override fun forward(tabId: String): HistoryOutcome = history(tabId) { id -> controller.goForwardOutcome(id) }

    /** Shared back/forward: read the generation, issue the command, then read the settled tab. */
    @Suppress("TooGenericExceptionCaught", "LongMethod", "ReturnCount", "SwallowedException")
    private fun history(
        tabId: String,
        command: (String) -> BrowserHistResult,
    ): HistoryOutcome {
        return try {
            val (result, genBefore) =
                onMain(SYNC_TIMEOUT_MS) {
                    val t = controller.tab(tabId)
                    if (t == null) null to 0L else command(tabId) to t.navigationGeneration
                }
            if (result == null || result is BrowserHistResult.NoTab) {
                return HistoryOutcome(HistStatus.NO_TAB, "", "", false, false, "")
            }
            if (result is BrowserHistResult.NoChange) {
                val t = liveTab(tabId)
                return HistoryOutcome(
                    HistStatus.NO_CHANGE,
                    t.url,
                    originOf(t),
                    t.canGoBack,
                    t.canGoForward,
                    result.reason,
                )
            }
            settle(tabId, genBefore)
            val t = liveTab(tabId)
            return HistoryOutcome(HistStatus.MOVED, t.url, originOf(t), t.canGoBack, t.canGoForward, "")
        } catch (e: MainHopTimeout) {
            HistoryOutcome(HistStatus.TIMED_OUT, "", "", false, false, "")
        }
    }

    @Suppress("SwallowedException")
    override fun reload(tabId: String): ReloadOutcome =
        try {
            val (result, genBefore) =
                onMain(SYNC_TIMEOUT_MS) {
                    val t = controller.tab(tabId)
                    if (t == null) null to 0L else controller.reloadOutcome(tabId) to t.navigationGeneration
                }
            if (result == null || result is BrowserReloadResult.NoTab) {
                return ReloadOutcome(ReloadStatus.NO_TAB, "", "", "")
            }
            if (result is BrowserReloadResult.NoChange) {
                val t = liveTab(tabId)
                return ReloadOutcome(ReloadStatus.NO_CHANGE, t.url, originOf(t), result.reason)
            }
            settle(tabId, genBefore)
            val t = liveTab(tabId)
            return ReloadOutcome(ReloadStatus.RELOADED, t.url, originOf(t), "")
        } catch (e: MainHopTimeout) {
            ReloadOutcome(ReloadStatus.TIMED_OUT, "", "", "")
        }

    @Suppress("SwallowedException")
    override fun snapshot(tabId: String): SnapshotOutcome =
        try {
            when (
                val result =
                    onMainAsync(
                        EVAL_TIMEOUT_MS,
                    ) { completer -> controller.snapshot(tabId) { completer(it) } }
            ) {
                is SnapshotResult.Success -> {
                    BrowserToolResultMapper.successSnapshot(result.snapshot)
                }

                is SnapshotResult.Failed -> {
                    BrowserToolResultMapper.failedSnapshot(
                        tabId,
                        when (result.failure) {
                            SnapshotFailure.PAGE_LOADING -> {
                                "page_loading: navigation has not settled; check browser.tabs before another snapshot"
                            }

                            SnapshotFailure.PAGE_ERROR -> {
                                "page_error: the tab has a load or policy error; inspect the page before retrying"
                            }

                            SnapshotFailure.HOST_UNAVAILABLE -> {
                                "host_unavailable: the page has no live browser host"
                            }

                            SnapshotFailure.PAGE_CHANGED -> {
                                "page_changed: the original observation expired; inspect the current tab again"
                            }

                            else -> {
                                result.failure.name.lowercase()
                            }
                        },
                    )
                }
            }
        } catch (e: MainHopTimeout) {
            BrowserToolResultMapper.failedSnapshot(tabId, "timed-out")
        }

    override fun find(
        tabId: String,
        query: String,
    ): FindOutcome {
        val snapshot = onMain(SYNC_TIMEOUT_MS) { controller.latestSnapshot(tabId) }
        if (snapshot == null) {
            return FindOutcome(false, tabId, query, 0, emptyList(), "no snapshot — take a snapshot first")
        }
        val expired =
            System.currentTimeMillis() - snapshot.mintedAtMillis >= SnapshotToken.DEFAULT_TTL_MILLIS ||
                controller
                    .tab(
                        tabId,
                    )?.let { it.isLoading || it.navigationGeneration != snapshot.navigationGeneration } !=
                false
        return BrowserObservationFacts.find(snapshot, query, expired)
    }

    override fun tabs(context: BrowserObservationContext): BrowserTabsOutcome =
        onMain(SYNC_TIMEOUT_MS) {
            val state = controller.state.value
            val allowed = state.tabs.filter { BrowserObservationFacts.allowed(it, context) }
            BrowserTabsOutcome(
                allowed.take(32).map { tab ->
                    val host = controller.hostView(tab.id) != null && controller.hostAvailable()
                    val url = BrowserObservationFacts.prefix(tab.url, BrowserTabView.MAX_URL_CHARS)
                    val title = BrowserObservationFacts.prefix(tab.title.orEmpty(), BrowserTabView.MAX_TITLE_CHARS)
                    BrowserTabView(
                        tab.id,
                        url,
                        title,
                        tab.id == state.selectedId,
                        when {
                            tab.error != null -> "ERROR"
                            tab.isLoading -> "LOADING"
                            !host || tab.navigationGeneration < 1 -> "NO_PAGE"
                            else -> "READY"
                        },
                        host,
                        tab.navigationGeneration,
                        url != tab.url,
                        title != tab.title.orEmpty(),
                    )
                },
                controller.hostAvailable(),
                allowed.size > 32,
            )
        }

    @Suppress("SwallowedException") // interruption is cancellation, latch timeout is a truthful read-only timeout
    override fun waitFor(request: BrowserWaitRequest): BrowserWaitOutcome {
        val deadline = minOf(request.deadline.toEpochMilli(), System.currentTimeMillis() + request.timeoutMillis)
        val timer = WaitTimer(request, deadline)
        if (!timer.active()) return timer.terminal()
        return try {
            val (binding, status) = onMain(timer.remaining(SYNC_TIMEOUT_MS)) { prepareWait(request) }
            if (binding == null) {
                BrowserWaitOutcome(status ?: BrowserWaitStatus.NO_PAGE)
            } else {
                waitOnDocument(request, binding, timer)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            BrowserWaitOutcome(BrowserWaitStatus.CANCELLED)
        } catch (timeout: MainHopTimeout) {
            timer.terminal()
        } finally {
            timer.close()
        }
    }

    private class WaitTimer(
        val request: BrowserWaitRequest,
        val deadline: Long,
    ) {
        private val worker = Thread.currentThread()
        private val ended = AtomicBoolean(false)
        private val monotonicEnd = System.nanoTime() + request.timeoutMillis * 1_000_000L

        fun remaining(ceiling: Long): Long =
            minOf(
                ceiling,
                deadline - System.currentTimeMillis(),
                (monotonicEnd - System.nanoTime()) / 1_000_000,
            ).coerceAtLeast(1)

        fun active(): Boolean {
            if (ended.get() || request.isCancelled() || worker.isInterrupted) return false
            return System.currentTimeMillis() < deadline && System.nanoTime() < monotonicEnd
        }

        fun close() {
            ended.set(true)
        }

        fun terminal(): BrowserWaitOutcome =
            BrowserWaitOutcome(
                if (request.isCancelled() || worker.isInterrupted) {
                    BrowserWaitStatus.CANCELLED
                } else {
                    BrowserWaitStatus.TIMED_OUT
                },
            )
    }

    private fun prepareWait(request: BrowserWaitRequest): Pair<BrowserObservationBinding?, BrowserWaitStatus?> {
        val tab = controller.tab(request.tabId)
        if (tab != null &&
            !BrowserObservationFacts.allowed(tab, request.context)
        ) {
            return null to BrowserWaitStatus.OUT_OF_SCOPE
        }
        val binding = controller.observationBinding(request.tabId)
        val failure = if (controller.hostAvailable()) BrowserWaitStatus.NO_PAGE else BrowserWaitStatus.HOST_UNAVAILABLE
        return binding to if (binding == null) failure else null
    }

    @Suppress("ReturnCount") // each original wait has one truthful terminal observation
    private fun waitOnDocument(
        request: BrowserWaitRequest,
        binding: BrowserObservationBinding,
        timer: WaitTimer,
    ): BrowserWaitOutcome {
        while (timer.active()) {
            val result =
                onMainAsync<SnapshotResult>(timer.remaining(EVAL_TIMEOUT_MS)) { complete ->
                    if (controller.observationState(binding) != null || !timer.active()) {
                        complete(SnapshotResult.Failed(com.helix.feature.browser.snapshot.SnapshotFailure.NO_PAGE))
                    } else {
                        controller.snapshot(request.tabId, timer::active, complete)
                    }
                }
            if (!timer.active()) return timer.terminal()
            val invalid = onMain(timer.remaining(SYNC_TIMEOUT_MS)) { controller.observationState(binding) }
            if (invalid != null) return BrowserWaitOutcome(invalid)
            if (result is SnapshotResult.Failed) return BrowserWaitOutcome(BrowserWaitStatus.NO_PAGE)
            val found = BrowserObservationFacts.find((result as SnapshotResult.Success).snapshot, request.query, false)
            val satisfied =
                when (request.condition) {
                    BrowserWaitCondition.PRESENT -> found.matchCount > 0
                    BrowserWaitCondition.ABSENT -> found.matchCount == 0 && !found.truncated
                }
            if (satisfied) return BrowserWaitOutcome(BrowserWaitStatus.SATISFIED, found)
            if (timer.active()) Thread.sleep(timer.remaining(150))
        }
        return timer.terminal()
    }

    override fun click(
        tabId: String,
        token: String,
    ): ActionOutcome = runAction(tabId, token, BrowserActionScript::click)

    override fun type(
        tabId: String,
        token: String,
        text: String,
    ): ActionOutcome = runAction(tabId, token) { nodeIndex -> BrowserActionScript.type(nodeIndex, text) }

    /**
     * Validates the node token against the tab's LIVE state (fail-closed), then runs the fixed
     * action script and re-validates the field with the host [SensitiveFieldClassifier]. The
     * script is built from the token's validated node index (so it never carries arbitrary text
     * beyond the `type` payload).
     */
    @Suppress("ReturnCount")
    private fun runAction(
        tabId: String,
        token: String,
        scriptFor: (Int) -> String,
    ): ActionOutcome {
        val nodeIndex = SnapshotToken.parse(token)?.nodeIndex ?: -1
        if (nodeIndex < 0) return ActionOutcome(ActionStatus.STALE_TOKEN, -1, "", "", "malformed-token")
        val verdict = onMain(SYNC_TIMEOUT_MS) { controller.verifyNodeToken(tabId, token) }
        if (verdict !is TokenVerdict.Valid) {
            val status = if (controller.tab(tabId) == null) ActionStatus.NO_TAB else ActionStatus.STALE_TOKEN
            return ActionOutcome(status, nodeIndex, "", "", verdictReason(verdict))
        }
        val outcome =
            onMainAsync(EVAL_TIMEOUT_MS) { completer ->
                controller.evaluateFixed(tabId, scriptFor(nodeIndex)) { completer(it) }
            }
        return when (outcome) {
            is EvalFixedOutcome.NoPage -> ActionOutcome(ActionStatus.STALE_TOKEN, nodeIndex, "", "", "page changed")
            is EvalFixedOutcome.Result -> BrowserToolResultMapper.mapAction(outcome.raw, nodeIndex)
        }
    }

    @Suppress("SwallowedException")
    override fun scroll(
        tabId: String,
        dx: Int,
        dy: Int,
    ): ScrollOutcome =
        try {
            when (
                val outcome =
                    onMainAsync(EVAL_TIMEOUT_MS) { completer ->
                        controller.evaluateFixed(tabId, BrowserActionScript.scroll(dx, dy)) { completer(it) }
                    }
            ) {
                is EvalFixedOutcome.NoPage -> {
                    ScrollOutcome(
                        ScrollStatus.NO_PAGE,
                        dx,
                        dy,
                        "no scrollable page in that tab",
                    )
                }

                is EvalFixedOutcome.Result -> {
                    BrowserToolResultMapper.mapScroll(outcome.raw, dx, dy)
                }
            }
        } catch (e: MainHopTimeout) {
            ScrollOutcome(ScrollStatus.TIMED_OUT, dx, dy, "")
        }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun screenshot(tabId: String): ScreenshotOutcome {
        return try {
            val png = onMain(SYNC_TIMEOUT_MS) { controller.capturePagePng(tabId) }
            if (png ==
                null
            ) {
                return ScreenshotOutcome(ScreenshotStatus.NO_PAGE, "", 0L, "", "no captured page in that tab")
            }
            // The capture ran on the main thread; the disk write is intentionally off-main here.
            val path = FileScopePath(scopeId, "output/browser-" + UUID.randomUUID() + ".png")
            val outcome = workspaceStore.writeArtifact(path, png, WorkspaceLayout.OUTPUT)
            ScreenshotOutcome(
                ScreenshotStatus.SAVED,
                path.toModelReference(),
                outcome.record.sizeBytes,
                outcome.record.sha256,
                "",
            )
        } catch (e: MainHopTimeout) {
            ScreenshotOutcome(ScreenshotStatus.TIMED_OUT, "", 0L, "", "")
        } catch (e: Exception) {
            // Fail-closed: any capture/save failure (quota, disk, bad path) is an ERROR outcome —
            // the bytes are never partially published.
            ScreenshotOutcome(ScreenshotStatus.ERROR, "", 0L, "", "could not save screenshot")
        }
    }

    /**
     * Downloads [url] into the Workspace (doc 09 §3.4). Delegates to [downloader] — the pure-JVM
     * executor (real redirect resolution + capped streaming write) that the JVM unit test drives
     * end-to-end against a local server. No main-thread hop: it is pure HTTP + workspace I/O.
     */
    override fun download(
        url: String,
        suggestedName: String,
    ): DownloadOutcome = downloader.download(url, suggestedName)

    // ---------------------------------------------------------------- mapping

    // ---------------------------------------------------------------- helpers

    /** The tab's live state from the published (thread-safe) state; null when unknown. */
    private fun liveTab(tabId: String): BrowserTab {
        val tab = controller.tab(tabId) ?: return BrowserTab(tabId)
        return tab
    }

    private fun originOf(tab: BrowserTab): String = BrowserOrigin.of(tab.url).orEmpty()

    /** A model-readable reason for a non-Valid token verdict (the sealed verdict has no [Enum.name]). */
    private fun verdictReason(verdict: TokenVerdict): String =
        when (verdict) {
            TokenVerdict.Valid -> "valid"
            TokenVerdict.MalformedToken -> "malformed-token"
            TokenVerdict.WrongTab -> "wrong-tab"
            TokenVerdict.StaleOrigin -> "stale-origin"
            TokenVerdict.StaleGeneration -> "stale-generation"
            TokenVerdict.StaleFingerprint -> "stale-fingerprint"
            TokenVerdict.Expired -> "expired"
        }

    /**
     * Blocks (off-main) until the tab settles after a navigation: its navigation generation has
     * advanced past [genBefore] and it is no longer loading — or an error page / the deadline
     * ends the wait. Reads the thread-safe published state, never the main-thread-only machine.
     */
    @Suppress("ReturnCount")
    private fun settle(
        tabId: String,
        genBefore: Long,
    ) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val tab = controller.tab(tabId) ?: return
            if (tab.error != null) return
            if (!tab.isLoading && tab.navigationGeneration > genBefore) return
            try {
                Thread.sleep(SETTLE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // the main-thread block may throw anything; it is rethrown off-main
    private fun <T> onMain(
        timeoutMs: Long,
        block: () -> T,
    ): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val box = AtomicReference<Any?>()
        var failure: Throwable? = null
        mainHandler.post {
            try {
                box.set(block())
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        ) {
            throw MainHopTimeout("main-thread hop timed out after ${timeoutMs}ms")
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }

    /**
     * Posts [block] to the main thread and blocks until its [completer] is called (exactly
     * once, on the main thread) or the deadline elapses. Must NOT be called on the main thread.
     */
    private fun <T> onMainAsync(
        timeoutMs: Long,
        block: (completer: (T) -> Unit) -> Unit,
    ): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            error("onMainAsync must not be called on the main thread")
        }
        val latch = CountDownLatch(1)
        val box = AtomicReference<T?>()
        val done = AtomicBoolean(false)
        val completer: (T) -> Unit =
            { value ->
                if (done.compareAndSet(false, true)) {
                    box.set(value)
                    latch.countDown()
                }
            }
        mainHandler.post { block(completer) }
        if (!latch.await(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        ) {
            throw MainHopTimeout("main-thread eval timed out after ${timeoutMs}ms")
        }
        @Suppress("UNCHECKED_CAST")
        return box.get() as T
    }

    private companion object {
        /** Bounded for the host-side sensitive-field gate on the action result. */

        const val SYNC_TIMEOUT_MS = 3_000L
        const val EVAL_TIMEOUT_MS = 7_000L
        const val SETTLE_TIMEOUT_MS = 6_000L
        const val SETTLE_POLL_MS = 50L
    }

    /** Raised when a main-thread hop / eval outlives its latch deadline (a genuine hang). */
    private class MainHopTimeout(
        message: String,
    ) : RuntimeException(message)
}
