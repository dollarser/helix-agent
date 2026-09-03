package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.feature.browser.snapshot.BrowserActionScript
import com.helix.feature.browser.snapshot.BrowserOrigin
import com.helix.feature.browser.snapshot.SnapshotResult
import com.helix.feature.browser.snapshot.SnapshotToken
import com.helix.feature.browser.snapshot.TokenVerdict
import com.helix.tools.browser.ActionOutcome
import com.helix.tools.browser.ActionStatus
import com.helix.tools.browser.BrowserFindMatch
import com.helix.tools.browser.BrowserNodeView
import com.helix.tools.browser.BrowserToolBridge
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    override fun open(url: String): OpenOutcome {
        val result = onMain(SYNC_TIMEOUT_MS) { controller.openTab(url) }
        return OpenOutcome(result.tabId, result.url, result.origin)
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
                is SnapshotResult.Success -> successSnapshot(result.snapshot)
                is SnapshotResult.Failed -> failedSnapshot(tabId, result.failure.name.lowercase())
            }
        } catch (e: MainHopTimeout) {
            failedSnapshot(tabId, "timed-out")
        }

    override fun find(
        tabId: String,
        query: String,
    ): FindOutcome {
        val snapshot = onMain(SYNC_TIMEOUT_MS) { controller.latestSnapshot(tabId) }
        if (snapshot == null) {
            return FindOutcome(false, tabId, query, 0, emptyList(), "no snapshot yet — take a snapshot first")
        }
        val needle = query.lowercase()
        val matches = ArrayList<BrowserFindMatch>()
        for (node in snapshot.nodes) {
            if (matches.size >= MAX_FIND_MATCHES) break
            val haystack =
                listOf(node.text.text, node.name?.text.orEmpty(), node.role, node.href?.text.orEmpty())
                    .joinToString(" ")
                    .lowercase()
            if (needle.isNotEmpty() && haystack.contains(needle)) {
                matches.add(BrowserFindMatch(node.index, node.role, node.text.text, node.token))
            }
        }
        return FindOutcome(true, tabId, query, matches.size, matches, "")
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
            is EvalFixedOutcome.Result -> mapAction(outcome.raw, nodeIndex)
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
                    mapScroll(outcome.raw, dx, dy)
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

    // ---------------------------------------------------------------- mapping

    private fun successSnapshot(s: com.helix.feature.browser.snapshot.BrowserSnapshot): SnapshotOutcome =
        SnapshotOutcome(
            ok = true,
            tabId = s.tabId,
            url = s.url.text,
            title = s.title.text,
            origin = s.origin,
            navigationGeneration = s.navigationGeneration,
            fingerprint = s.fingerprint,
            truncated = s.truncated,
            nodeCount = s.nodeCount,
            nodes =
                s.nodes.map { n ->
                    BrowserNodeView(
                        index = n.index,
                        role = n.role,
                        text = n.text.text,
                        value = n.value?.text.orEmpty(),
                        href = n.href?.text.orEmpty(),
                        name = n.name?.text.orEmpty(),
                        token = n.token,
                    )
                },
            message = "",
        )

    private fun failedSnapshot(
        tabId: String,
        message: String,
    ): SnapshotOutcome =
        SnapshotOutcome(
            ok = false,
            tabId = tabId,
            url = "",
            title = "",
            origin = "",
            navigationGeneration = 0,
            fingerprint = "",
            truncated = false,
            nodeCount = 0,
            nodes = emptyList(),
            message = message,
        )

    /**
     * Maps a fixed action script's raw JSON result to an [ActionOutcome], applying the host
     * sensitive-field re-validation (authoritative, fail-closed over the script's own gate).
     */
    @Suppress("ReturnCount")
    private fun mapAction(
        raw: String?,
        nodeIndex: Int,
    ): ActionOutcome {
        if (raw == null) return ActionOutcome(ActionStatus.TIMED_OUT, nodeIndex, "", "", "")
        val obj = parseActionRaw(raw) ?: return ActionOutcome(ActionStatus.ERROR, nodeIndex, "", "", "bad result")
        val tag = obj.str("tag")
        val role = obj.str("role")
        val status = obj.str("status")
        val verdict =
            SensitiveFieldClassifier.classify(
                tag = tag,
                type = obj.str("type"),
                autocomplete = obj.str("autocomplete"),
                nameId = obj.str("nameId"),
                placeholder = obj.str("placeholder"),
            )
        if (verdict is SensitiveFieldClassifier.Verdict.Sensitive) {
            return ActionOutcome(ActionStatus.REFUSED, nodeIndex, tag, role, SensitiveFieldClassifier.reasonOf(verdict))
        }
        return when (status) {
            "performed" -> {
                ActionOutcome(ActionStatus.PERFORMED, nodeIndex, tag, role, "")
            }

            "refused" -> {
                ActionOutcome(
                    ActionStatus.REFUSED,
                    nodeIndex,
                    tag,
                    role,
                    obj.str("reason").ifEmpty { "sensitive-field" },
                )
            }

            "not-a-field" -> {
                ActionOutcome(ActionStatus.REFUSED, nodeIndex, tag, role, "not-a-field")
            }

            "not-found" -> {
                ActionOutcome(ActionStatus.STALE_TOKEN, nodeIndex, tag, role, "not-found")
            }

            "" -> {
                ActionOutcome(ActionStatus.ERROR, nodeIndex, tag, role, "bad result")
            }

            else -> {
                ActionOutcome(ActionStatus.ERROR, nodeIndex, tag, role, status)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun mapScroll(
        raw: String?,
        dx: Int,
        dy: Int,
    ): ScrollOutcome {
        if (raw == null) return ScrollOutcome(ScrollStatus.TIMED_OUT, dx, dy, "")
        val obj = parseActionRaw(raw) ?: return ScrollOutcome(ScrollStatus.ERROR, dx, dy, "bad result")
        return if (obj.bool("ok")) {
            ScrollOutcome(ScrollStatus.SCROLLED, dx, dy, "")
        } else {
            ScrollOutcome(ScrollStatus.ERROR, dx, dy, "scroll failed")
        }
    }

    @Suppress("SwallowedException")
    private fun parseActionRaw(raw: String): JsonObject? =
        try {
            actionJson.parseToJsonElement(raw) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

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
        val actionJson = Json { ignoreUnknownKeys = true }

        const val SYNC_TIMEOUT_MS = 3_000L
        const val EVAL_TIMEOUT_MS = 7_000L
        const val SETTLE_TIMEOUT_MS = 6_000L
        const val SETTLE_POLL_MS = 50L
        const val MAX_FIND_MATCHES = 50
    }

    /** Raised when a main-thread hop / eval outlives its latch deadline (a genuine hang). */
    private class MainHopTimeout(
        message: String,
    ) : RuntimeException(message)

    private fun JsonObject.str(key: String): String =
        this[key]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
}
