package com.helix.tools.browser

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-062 (verification matrix row `:tools:browser:test`): the browser.* tools' fail-closed
 * outcome → result mapping. A [FakeBridge] returns canned [BrowserToolBridge] outcomes; each
 * tool's executor must never claim success on a refusal, a stale token, an unknown tab or a
 * timeout. The mapping IS the tool layer — the WebView / token / policy work lives in the port
 * impl (:feature:browser), so this exercises the model-facing contract in isolation.
 */
class BrowserToolsMappingTest {
    private val bridge = FakeBridge()

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(
        name: String,
        args: JsonObject,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = name,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = noCancel,
        )

    private fun run(
        name: String,
        executor: ToolExecutor,
        args: JsonObject,
    ): ToolExecutorResult = executor.execute(call(name, args))

    private fun json(result: ToolExecutorResult): JsonObject {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return c.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): ToolExecutorResult.Failed {
        val f = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return f
    }

    // ── open ───────────────────────────────────────────────────────────────────────────

    @Test
    fun openReportsTheNewTab() {
        bridge.openResult = OpenOutcome("t7", "https://example.com/", "example.com")
        val out =
            json(
                run(
                    BrowserOpenTool.NAME,
                    BrowserOpenTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://example.com/")) },
                ),
            )
        assertEquals("t7", out.getValue("tabId").jsonPrimitive.content)
        assertEquals("https://example.com/", out.getValue("url").jsonPrimitive.content)
        assertEquals("example.com", out.getValue("origin").jsonPrimitive.content)
    }

    // ── navigate: the policy / unknown-tab / timeout outcomes ──────────────────────────

    @Test
    fun navigateStartedEmitsTheCommittedUrlWithEmptyReason() {
        bridge.navResult = NavigateOutcome(NavStatus.STARTED, "https://a.example/", "a.example", "")
        val out =
            json(
                run(
                    BrowserNavigateTool.NAME,
                    BrowserNavigateTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("url", JsonPrimitive("https://a.example/"))
                    },
                ),
            )
        assertEquals("started", out.getValue("status").jsonPrimitive.content)
        assertEquals("https://a.example/", out.getValue("url").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun navigateDeniedKeepsTheReasonAndEmitsAnEmptyUrl() {
        bridge.navResult = NavigateOutcome(NavStatus.DENIED, "", "", "scheme not allowed")
        val out =
            json(
                run(
                    BrowserNavigateTool.NAME,
                    BrowserNavigateTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("url", JsonPrimitive("file:///etc/passwd"))
                    },
                ),
            )
        assertEquals("denied", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("url").jsonPrimitive.content)
        assertEquals("scheme not allowed", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun navigateUnknownTabIsAFailClosedFailure() {
        bridge.navResult = NavigateOutcome(NavStatus.NO_TAB, "", "", "")
        val f =
            failed(
                run(
                    BrowserNavigateTool.NAME,
                    BrowserNavigateTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("nope"))
                        put("url", JsonPrimitive("https://a.example/"))
                    },
                ),
            )
        assertTrue(f.detail.contains("unknown tab"))
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun navigateTimeoutIsATimedOutResult() {
        bridge.navResult = NavigateOutcome(NavStatus.TIMED_OUT, "", "", "")
        val r =
            run(
                BrowserNavigateTool.NAME,
                BrowserNavigateTool.executor(bridge),
                buildJsonObject {
                    put("tabId", JsonPrimitive("t1"))
                    put("url", JsonPrimitive("https://a.example/"))
                },
            )
        assertEquals(ToolExecutorResult.TimedOut, r)
    }

    // ── back / forward / reload history outcomes ───────────────────────────────────────

    @Test
    fun backMovedEmitsTheResultingFlags() {
        bridge.backResult = HistoryOutcome(HistStatus.MOVED, "https://a.example/", "a.example", true, false, "")
        val out =
            json(
                run(
                    BrowserBackTool.NAME,
                    BrowserBackTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("moved", out.getValue("status").jsonPrimitive.content)
        assertEquals(
            true,
            out
                .getValue("canGoBack")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            false,
            out
                .getValue("canGoForward")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun backNoChangeKeepsTheReason() {
        bridge.backResult =
            HistoryOutcome(HistStatus.NO_CHANGE, "https://a.example/", "a.example", false, false, "no history")
        val out =
            json(
                run(
                    BrowserBackTool.NAME,
                    BrowserBackTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("no-change", out.getValue("status").jsonPrimitive.content)
        assertEquals("no history", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun reloadUnknownTabIsAFailClosedFailure() {
        bridge.reloadResult = ReloadOutcome(ReloadStatus.NO_TAB, "", "", "")
        val f =
            failed(
                run(
                    BrowserReloadTool.NAME,
                    BrowserReloadTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("nope")) },
                ),
            )
        assertTrue(f.detail.contains("unknown tab"))
        assertTrue(f.sideEffectFree)
    }

    // ── click / type: the token + sensitive-field outcomes ─────────────────────────────

    @Test
    fun clickPerformedEmitsPerformedWithAnEmptyReason() {
        bridge.clickResult = ActionOutcome(ActionStatus.PERFORMED, 3, "button", "button", "")
        val out =
            json(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("performed", out.getValue("status").jsonPrimitive.content)
        assertEquals("3", out.getValue("nodeIndex").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clickRefusedEmitsTheRefusalCategoryNeverPerformed() {
        bridge.clickResult = ActionOutcome(ActionStatus.REFUSED, 5, "input", "field", "payment")
        val out =
            json(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals("payment", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clickStaleTokenIsACompletedStaleTokenNotAFailure() {
        bridge.clickResult = ActionOutcome(ActionStatus.STALE_TOKEN, 7, "", "", "stale-fingerprint")
        val out =
            json(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("stale-token", out.getValue("status").jsonPrimitive.content)
        assertEquals("stale-fingerprint", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clickUnknownTabIsAFailClosedFailure() {
        bridge.clickResult = ActionOutcome(ActionStatus.NO_TAB, -1, "", "", "")
        val f =
            failed(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("nope"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertTrue(f.detail.contains("unknown tab"))
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun clickErrorIsATerminalFailure() {
        bridge.clickResult = ActionOutcome(ActionStatus.ERROR, 1, "input", "field", "bad result")
        val f =
            failed(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("bad result", f.detail)
    }

    @Test
    fun typeTimeoutIsATimedOutResult() {
        bridge.typeResult = ActionOutcome(ActionStatus.TIMED_OUT, 1, "", "", "")
        val r =
            run(
                BrowserTypeTool.NAME,
                BrowserTypeTool.executor(bridge),
                buildJsonObject {
                    put("tabId", JsonPrimitive("t1"))
                    put("token", JsonPrimitive("tok"))
                    put("text", JsonPrimitive("x"))
                },
            )
        assertEquals(ToolExecutorResult.TimedOut, r)
    }

    // ── snapshot / find ────────────────────────────────────────────────────────────────

    @Test
    fun snapshotFailureIsAFailClosedFailure() {
        bridge.snapshotResult = SnapshotOutcome(false, "t1", "", "", "", 0, "", false, 0, emptyList(), "no page")
        val f =
            failed(
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertTrue(f.detail.contains("no page"))
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun findWithoutASnapshotIsAFailClosedFailure() {
        bridge.findResult = FindOutcome(false, "t1", "x", 0, emptyList(), "no snapshot yet")
        val f =
            failed(
                run(
                    BrowserFindTool.NAME,
                    BrowserFindTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("query", JsonPrimitive("x"))
                    },
                ),
            )
        assertTrue(f.detail.contains("no snapshot yet"))
        assertTrue(f.sideEffectFree)
    }

    // ── scroll / screenshot ────────────────────────────────────────────────────────────

    @Test
    fun scrollNoPageIsAFailClosedFailure() {
        bridge.scrollResult = ScrollOutcome(ScrollStatus.NO_PAGE, 0, 100, "")
        val f =
            failed(
                run(
                    BrowserScrollTool.NAME,
                    BrowserScrollTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("dx", JsonPrimitive(0))
                        put("dy", JsonPrimitive(100))
                    },
                ),
            )
        assertEquals("no scrollable page in that tab", f.detail)
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun screenshotSavedEmitsTheModelSafeReferenceAndAudit() {
        bridge.screenshotResult =
            ScreenshotOutcome(ScreenshotStatus.SAVED, "scope:app:output/browser-1.png", 2048L, "ab12", "")
        val r =
            run(
                BrowserScreenshotTool.NAME,
                BrowserScreenshotTool.executor(bridge),
                buildJsonObject { put("tabId", JsonPrimitive("t1")) },
            )
        val out = json(r)
        assertEquals("saved", out.getValue("status").jsonPrimitive.content)
        assertEquals("scope:app:output/browser-1.png", out.getValue("reference").jsonPrimitive.content)
        assertEquals("2048", out.getValue("sizeBytes").jsonPrimitive.content)
        assertEquals("ab12", out.getValue("sha256").jsonPrimitive.content)
        // The audit detail mirrors the reference; the model output never carries a raw path.
        val c = r as ToolExecutorResult.Completed
        assertEquals(
            "scope:app:output/browser-1.png",
            c.auditDetail
                ?.getValue("reference")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun screenshotNoPageIsAFailClosedFailure() {
        bridge.screenshotResult = ScreenshotOutcome(ScreenshotStatus.NO_PAGE, "", 0L, "", "")
        val f =
            failed(
                run(
                    BrowserScreenshotTool.NAME,
                    BrowserScreenshotTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("no captured page in that tab", f.detail)
        assertTrue(f.sideEffectFree)
    }

    /** Returns one canned outcome per port method; each test sets the field it exercises. */
    private class FakeBridge : BrowserToolBridge {
        var openResult: OpenOutcome = OpenOutcome("t1", "https://example.com/", "example.com")
        var navResult: NavigateOutcome =
            NavigateOutcome(NavStatus.STARTED, "https://example.com/", "example.com", "")
        var backResult: HistoryOutcome =
            HistoryOutcome(HistStatus.MOVED, "https://example.com/", "example.com", true, false, "")
        var reloadResult: ReloadOutcome =
            ReloadOutcome(ReloadStatus.RELOADED, "https://example.com/", "example.com", "")
        var snapshotResult: SnapshotOutcome =
            SnapshotOutcome(
                true,
                "t1",
                "https://example.com/",
                "Example",
                "example.com",
                1,
                "fp",
                false,
                0,
                emptyList(),
                "",
            )
        var findResult: FindOutcome = FindOutcome(true, "t1", "x", 0, emptyList(), "")
        var clickResult: ActionOutcome = ActionOutcome(ActionStatus.PERFORMED, 0, "button", "button", "")
        var typeResult: ActionOutcome = ActionOutcome(ActionStatus.PERFORMED, 0, "input", "field", "")
        var scrollResult: ScrollOutcome = ScrollOutcome(ScrollStatus.SCROLLED, 0, 100, "")
        var screenshotResult: ScreenshotOutcome =
            ScreenshotOutcome(ScreenshotStatus.SAVED, "scope:app:output/browser-x.png", 1L, "sha", "")

        override fun open(url: String): OpenOutcome = openResult

        override fun navigate(
            tabId: String,
            url: String,
        ): NavigateOutcome = navResult

        override fun back(tabId: String): HistoryOutcome = backResult

        override fun forward(tabId: String): HistoryOutcome = backResult

        override fun reload(tabId: String): ReloadOutcome = reloadResult

        override fun snapshot(tabId: String): SnapshotOutcome = snapshotResult

        override fun find(
            tabId: String,
            query: String,
        ): FindOutcome = findResult

        override fun click(
            tabId: String,
            token: String,
        ): ActionOutcome = clickResult

        override fun type(
            tabId: String,
            token: String,
            text: String,
        ): ActionOutcome = typeResult

        override fun scroll(
            tabId: String,
            dx: Int,
            dy: Int,
        ): ScrollOutcome = scrollResult

        override fun screenshot(tabId: String): ScreenshotOutcome = screenshotResult
    }
}
