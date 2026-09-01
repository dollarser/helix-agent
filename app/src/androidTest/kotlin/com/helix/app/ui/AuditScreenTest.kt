package com.helix.app.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-036 audit log page (roadmap: 同任务交付审计日志页): real dispatches through the
 * production container (dispatcher + storage-backed sink) land as REDACTED rows; the page
 * lists them newest-first and filters by 会话 / 工具 / 风险 / 日期. The page never shows an
 * argument or output body: the sink stores an allowlisted payload and the record type has
 * no slot for content — asserted here by dispatching a call whose arguments carry a
 * distinctive marker and verifying the marker never reaches the audit page.
 */
@RunWith(AndroidJUnit4::class)
class AuditScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var container: AppContainer

    /** A distinctive marker that must NEVER appear on the audit page. */
    private val secretMarker = "hxasecret-7f3a9c-marker"

    @Before
    fun setUp() {
        container = (composeRule.activity.application as HelixApplication).appContainer
        // Register the audit-page test tool (L2) exactly once per process.
        val name = ToolName(AUDIT_TOOL_NAME)
        if (container.toolPipeline.registry.resolveLatest(name) == null) {
            val descriptor =
                ToolDescriptor(
                    name = name,
                    version = ToolVersion(1),
                    description = "audit page test tool",
                    inputSchema =
                        Json
                            .parseToJsonElement(
                                """{"type":"object","properties":{"path":{"type":"string"}},""" +
                                    """"required":["path"],"additionalProperties":false}""",
                            ).let { it as JsonObject },
                    outputSchema =
                        Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                    operationClass = ToolOperationClass.LOCAL_MUTATION,
                    baseRisk = RiskLevel.L2,
                    timeout = 30.seconds,
                    maxOutputBytes = 4096L,
                    requiredCapabilities = emptySet(),
                    idempotency = Idempotency.NON_IDEMPOTENT,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    origin = ToolOrigin.BuiltInOrigin,
                )
            container.toolPipeline.registry.register(descriptor)
            container.toolPipeline.implementations.register(
                descriptor,
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                        ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
                },
            )
        }
    }

    @Test
    fun auditPageShowsRedactedRowsAndFiltersByTool() {
        // Seed: one denied dispatch of the audit tool with a marker in its arguments.
        seedDeniedDispatch("audit-call-${System.nanoTime()}")

        composeRule.navigateTo("audit")

        // The first page load (unfiltered, on IO) must land: the seeded row is visible, and
        // the tool pick list is published in the SAME emit — so it is populated by now.
        assertTrue(
            "the seeded row must be on the unfiltered page",
            awaitAuditRows().isNotEmpty(),
        )

        // The tool filter narrows the page to the audit tool's rows. EXACT text match: the
        // audit rows themselves contain the tool name (substring would match two nodes).
        composeRule.onNodeWithTag("audit-filter-tool").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(AUDIT_TOOL_NAME).performClick()
        composeRule.waitForIdle()

        // The service reloads the page on IO: poll (bounded) until the filtered page lands.
        val rows = awaitAuditRows()
        assertTrue("the audit tool's row must be on the page", rows.isNotEmpty())
        // Every row shown is an audit-tool row (the filter did its job).
        val pageText = rows.joinToString(" ") { node -> nodeText(node) }
        // REDACTION: the marker travels in the arguments (persisted in tool_calls) but the
        // audit payload is allowlisted — the marker must not appear on the page.
        assertTrue("argument body leaked to the audit page", !pageText.contains(secretMarker))
        assertTrue("the row must show the stable code", pageText.contains("用户拒绝审批"))
        assertTrue("the row must show the tool name", pageText.contains(AUDIT_TOOL_NAME))
        assertTrue("the row must show the dynamic risk", pageText.contains("L2（需逐次批准）"))

        // Clearing the filters restores the unfiltered page (the row is still there).
        composeRule.onNodeWithTag("audit-clear-filters").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "the row must survive clearing the filters",
            awaitAuditRows().isNotEmpty(),
        )
    }

    /** Polls (bounded) until the audit page has rendered at least one row: the service
     * reloads the bounded page off the main thread, so the filtered list lands a tick
     * after the filter change. */
    private fun awaitAuditRows(): List<SemanticsNode> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val rows = auditRows()
            if (rows.isNotEmpty()) return rows
            composeRule.waitForIdle()
            Thread.sleep(50)
        }
        return auditRows()
    }

    private fun auditRows(): List<SemanticsNode> =
        composeRule
            .onAllNodes(SemanticsMatcher("all") { true }, true)
            .fetchSemanticsNodes()
            .filter { node ->
                (
                    if (node.config.contains(SemanticsProperties.TestTag)) {
                        node.config.get(SemanticsProperties.TestTag)
                    } else {
                        null
                    }
                )?.startsWith("audit-row-") == true
            }

    /** A row's own text plus all descendant text (the row's lines are child Text nodes). */
    private fun nodeText(node: SemanticsNode): String =
        node.config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList<String>() }
            .joinToString("") +
            node.config
                .getOrElse(SemanticsProperties.Text) { emptyList<AnnotatedString>() }
                .joinToString("") { it.text } +
            node.children.joinToString("") { nodeText(it) }

    /**
     * Seeds one denied dispatch of [AUDIT_TOOL_NAME] with the marker argument, through the
     * production per-call pipeline (persist the tool_call row with canonical args,
     * dispatch, settle). The session + turn rows must exist for the tool_calls foreign
     * keys; the dispatch runs on a worker thread (the broker blocks on the user's
     * decision, so this must never run on the test's main instrumentation thread).
     */
    private fun seedDeniedDispatch(callId: String) {
        val args = buildJsonObject { put("path", secretMarker) }
        container.toolPipeline.registry.resolveLatest(ToolName(AUDIT_TOOL_NAME))
            ?: error("audit tool not registered")
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == "audit-session" }
        ) {
            container.storage.sessions.create("audit-session", "audit session", null, null, now)
        }
        if (container.storage.turns
                .listBySession("audit-session")
                .none { it.id == "audit-turn-1" }
        ) {
            container.storage.turns.start("audit-turn-1", "audit-session", now)
        }
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val thread =
            Thread {
                outcome[0] =
                    container.chatService.dispatchToolCall(
                        callId,
                        "audit-turn-1",
                        AUDIT_TOOL_NAME,
                        args.toString(),
                    )
                latch.countDown()
            }
        thread.isDaemon = true
        thread.start()
        // Wait for the pending card, then deny it (the production broker polls at 500 ms).
        container.chatService.denyApproval(approvalIdOf(callId))
        assertTrue("the dispatch must finish", latch.await(30, TimeUnit.SECONDS))
        assertTrue(
            "the seeded dispatch must be denied: ${outcome[0]}",
            outcome[0] is ToolDispatchOutcome.Denied,
        )
    }

    /**
     * Polls the LIVE timeline card for [toolCallId] until it is attached (the row is
     * published "处理中" BEFORE the broker attaches the pending card, so the probe must be
     * null-safe: `firstOrNull` + `?.card`, never `.first` / `!!` — the dispatch thread
     * publishes asynchronously after `start()`).
     */
    private fun approvalIdOf(toolCallId: String): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            container.chatService.screen.value.toolTimeline
                .firstOrNull { it.callId == toolCallId }
                ?.card
                ?.let { return it.approvalId }
            Thread.sleep(100)
        }
        error("no pending card for $toolCallId")
    }

    companion object {
        const val AUDIT_TOOL_NAME = "hxatest.auditrow"
    }
}
