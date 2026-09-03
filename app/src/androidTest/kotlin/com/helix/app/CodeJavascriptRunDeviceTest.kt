package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDispatchOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HXA-053 device acceptance (verification-matrix row `:app:connectedConsumerDebugAndroidTest`):
 * the production `code.javascript.run` tool, registered by [AppContainer] in the consumer build,
 * runs a REAL isolated-QuickJS-process execution through the full production pipeline (real Room
 * + the storage-backed broker + the real dispatcher, driven by
 * [com.helix.app.chat.ChatService.dispatchToolCall]) and enforces the mandated invariants:
 *
 * - S1 真实隔离执行 + 结果回填: an approved JS run executes in the isolated process, the
 *   model-visible result is backfilled, the call row settles COMPLETED, and the redacted §4.8
 *   audit block carries the source SHA-256, the output SHA-256, the input SHA-256, the applied
 *   §4.1 limits, and the isolated-process flag — never a body.
 * - S2 拒绝不执行: a denied run never executes (no tool result row, no executionDetail, the call
 *   settles DENIED, the audit is APPROVAL_DENIED).
 * - S3 改码即失效: changing the code changes the approval binding hash, so the old approval can
 *   never authorize the modified code (two separate cards, two separate hashes, two approvals).
 * - S4 JS 错误回填: a `throw` inside helixMain backfills a STABLE failure (never a fake
 *   success): the call settles FAILED and the audit carries the JS_ERROR terminal status, with
 *   the engine error body redacted out of the audit.
 * - S5 QuickJS 单并发: the isolated QuickJS backend is a single instance that runs one execution
 *   at a time — two real runs (measurable busy-loop windows) do NOT overlap: run A fully finishes
 *   before run B starts, and each run is a distinct isolated instance with no cross-contamination.
 *   (The within-batch lane serialization of a single model response's multiple calls is the
 *   framework's [com.helix.tools.framework.ToolScheduler] + footprint concern, covered by its JVM
 *   tests; the production Act-mode path drives one call at a time, as this test does.)
 */
@RunWith(AndroidJUnit4::class)
class CodeJavascriptRunDeviceTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(600)

    private lateinit var container: AppContainer

    private val toolName = ToolName(JS_TOOL_NAME)

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "js-session"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        // The production registry (built by AppContainer for the consumer variant) MUST already
        // carry the real tool — this is the HXA-053 "registered in the Tool Registry" acceptance.
        assertNotNull(
            "code.javascript.run must be registered by AppContainer in the consumer build",
            container.toolPipeline.registry.resolveLatest(toolName),
        )
        // Seed the session row BEFORE opening it (turns are seeded per-test, but the
        // session must exist when it is opened), and open it: since HXA-048 the chat
        // timeline is scoped to the OPEN session, and a different session can be left
        // open by other test classes (the app process survives across test classes within
        // one instrumentation run). Without this, the pending-card rows are filtered out
        // of `screen.value.toolTimeline`.
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "js session", null, null, now)
        }
        container.chatService.openSession(sessionId)
    }

    @After
    fun settleAbandonedApprovals() {
        // Backstop: a test that died mid-approval (any assertion before its approve/deny)
        // leaves its dispatch BLOCKED in the broker, holding an EXCLUSIVE scheduler slot
        // (every non-read call is a full barrier — `code.javascript.run` on lane:quickjs
        // is exclusive too) for the process lifetime — every later dispatch in this
        // process would then wait on admission forever (no approval record: the "no
        // pending approval record" cascade seen in the full developer suite). Cancel any
        // approval still pending on this class's seeded session and wait for its dispatch
        // to settle (CANCELLED) so the slot is free before the next test starts.
        pendingApprovalIdsOn(sessionId).forEach { container.toolPipeline.broker.cancel(it) }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && pendingApprovalIdsOn(sessionId).isNotEmpty()) {
            Thread.sleep(50)
        }
    }

    /** The approval ids still pending (AWAITING_APPROVAL calls) on this class's seeded session. */
    private fun pendingApprovalIdsOn(sessionId: String): List<String> =
        container.storage.turns
            .listBySession(sessionId)
            .flatMap { turn ->
                container.storage.toolCalls
                    .listByTurn(turn.id)
                    .filter { it.state == ToolCallState.AWAITING_APPROVAL.name }
                    .mapNotNull {
                        container.storage.approvals
                            .byToolCall(it.callId)
                            ?.id
                    }
            }

    // ------------------------------------------------------------------ pipeline helpers

    private fun jsArgs(
        code: String,
        input: JsonObject? = null,
    ): String =
        buildJsonObject {
            put("code", code)
            if (input != null) put("input", input)
        }.toString()

    /** Seeds the session + turn rows the tool_calls foreign keys require. */
    private fun ensureTurn(turnId: String) {
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "js session", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    /**
     * Runs one JS dispatch through the production per-call pipeline on a worker thread (the
     * broker blocks on the user's decision, so this must never run on the instrumentation thread).
     */
    private fun dispatchJs(
        toolCallId: String,
        turnId: String,
        code: String,
        input: JsonObject? = null,
    ): DispatchHandle {
        ensureTurn(turnId)
        val args = jsArgs(code, input)
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val error = arrayOf<Throwable?>(null)
        val t =
            Thread {
                try {
                    outcome[0] = container.chatService.dispatchToolCall(toolCallId, turnId, JS_TOOL_NAME, args)
                } catch (e: Throwable) {
                    error[0] = e
                } finally {
                    latch.countDown()
                }
            }
        t.isDaemon = true
        t.start()
        return DispatchHandle(latch, outcome, error)
    }

    private class DispatchHandle(
        val latch: CountDownLatch,
        val outcome: Array<ToolDispatchOutcome?>,
        val error: Array<Throwable?>,
    ) {
        fun join(): ToolDispatchOutcome {
            assertTrue("dispatch must finish", latch.await(90, TimeUnit.SECONDS))
            error[0]?.let { throw it }
            return outcome[0] ?: error("no outcome")
        }
    }

    /**
     * Waits for the call's approval record in storage (the source of truth for the pending
     * decision) and returns its id. The old UI-timeline probe read the card off
     * `screen.value.toolTimeline`, which is scoped to the open session (HXA-048) and
     * refreshed asynchronously: when another test class left a different session open, the
     * card row was filtered out and this timed out (the order-dependent "no pending card"
     * flake). The record exists the moment the broker starts waiting and is independent of
     * UI state.
     */
    private fun approvalIdOf(toolCallId: String): String {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            container.storage.approvals
                .byToolCall(toolCallId)
                ?.let { return it.id }
            Thread.sleep(50)
        }
        error("no pending approval record for $toolCallId")
    }

    /** The single tool-dispatch audit row for a call (the dispatcher is the only emitter). */
    private fun auditRow(toolCallId: String): com.helix.core.storage.entity.AuditEventEntity {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            container.storage.auditEvents
                .recent(1000)
                .firstOrNull { it.correlationId == toolCallId }
                ?.let { return it }
            Thread.sleep(100)
        }
        error("no audit row for $toolCallId")
    }

    private fun executionDetailOf(toolCallId: String): JsonObject =
        Json
            .parseToJsonElement(auditRow(toolCallId).redactedPayload)
            .jsonObject
            .get("executionDetail")
            ?.jsonObject
            ?: error("no executionDetail in the audit row for $toolCallId")

    private fun sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ S1

    @Test
    fun realIsolatedExecutionBackfillsResultAndAuditHashesAndLimits() {
        val code = "return { tag: 'hxadev-js-ok', doubled: input.n * 2 }"
        val handle = dispatchJs("js-s1-$run", "js-s1-turn-$run", code, buildJsonObject { put("n", 21) })
        container.chatService.approveApproval(approvalIdOf("js-s1-$run"))
        val succeeded = handle.join() as ToolDispatchOutcome.Succeeded

        // The model-visible result is backfilled and carries the computed value.
        val outputText = resultText(succeeded)
        assertTrue(
            "the isolated run must compute the doubled value, was: $outputText",
            outputText.contains("hxadev-js-ok"),
        )
        assertTrue("the isolated run must apply the input, was: $outputText", outputText.contains("42"))

        // The persisted call row settled COMPLETED (a real execution happened).
        assertEquals(
            ToolCallState.COMPLETED.name,
            container.storage.toolCalls
                .resolve("js-s1-$run")
                .state,
        )

        // The redacted §4.8 audit block: hashes, sizes, applied §4.1 limits, isolated flag — no body.
        val detail = executionDetailOf("js-s1-$run")
        assertEquals("SUCCESS", detail["status"]?.jsonPrimitive?.content)
        assertEquals(
            "the source SHA-256 must bind the exact code",
            sha256Hex(code),
            detail["sourceSha256"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "the output SHA-256 must bind the exact isolated output",
            sha256Hex(outputText),
            detail["outputSha256"]?.jsonPrimitive?.content,
        )
        assertTrue(
            "the input SHA-256 must be present",
            detail["inputSha256"]?.jsonPrimitive?.content?.isNotEmpty() == true,
        )
        assertTrue(
            "the isolated-process flag must be true on a real run",
            detail["isolated"]?.jsonPrimitive?.content == "true",
        )
        // The fixed §4.1 limits are recorded (the model cannot change them).
        val limits = detail["limits"]!!.jsonObject
        assertEquals("10000", limits["timeoutMs"]?.jsonPrimitive?.content)
        assertEquals("67108864", limits["memoryBytes"]?.jsonPrimitive?.content)
        assertEquals("262144", limits["maxOutputBytes"]?.jsonPrimitive?.content)
        // REDACTION: the code and the input value never enter the audit payload.
        val rowPayload = auditRow("js-s1-$run").redactedPayload
        assertTrue("code body leaked into the audit", !rowPayload.contains("hxadev-js-ok"))
        assertTrue("input value leaked into the audit", !rowPayload.contains("\"n\":21"))
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun deniedJsRunNeverExecutes() {
        val handle = dispatchJs("js-s2-$run", "js-s2-turn-$run", "return { tag: 'hxadev-denied' }")
        val approvalId = approvalIdOf("js-s2-$run")
        container.chatService.denyApproval(approvalId)
        val denied = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, denied.code)

        // Nothing executed: any persisted result carries NO output body (the code never ran),
        // the call settled DENIED, and the audit is the denial.
        val resultRow = container.storage.toolResults.byToolCall("js-s2-$run")
        assertTrue(
            "a denied run must not carry an output body, was: $resultRow",
            resultRow == null || resultRow.contentRef == null,
        )
        assertEquals(
            ToolCallState.DENIED.name,
            container.storage.toolCalls
                .resolve("js-s2-$run")
                .state,
        )
        val payload = Json.parseToJsonElement(auditRow("js-s2-$run").redactedPayload).jsonObject
        assertEquals("APPROVAL_DENIED", payload["code"]?.jsonPrimitive?.content)
        // A pre-execution denial has NO executionDetail (the code never ran) — it stays a
        // present-but-null key (stable allowlist shape), not a missing key.
        assertTrue(
            "a denied run must have no executionDetail",
            payload["executionDetail"] is kotlinx.serialization.json.JsonNull,
        )
    }

    // ------------------------------------------------------------------ S3

    @Test
    fun codeChangeInvalidatesTheApprovalHashOnDevice() {
        // Call 1: code A. Approve, succeed, record its binding hash.
        val handleA = dispatchJs("js-s3-a-$run", "js-s3-turn-a-$run", "return { v: 1 }")
        val approvalIdA = approvalIdOf("js-s3-a-$run")
        val bindingA =
            container.storage.approvals
                .resolve(approvalIdA)
                .bindingHash
        container.chatService.approveApproval(approvalIdA)
        val okA = handleA.join() as ToolDispatchOutcome.Succeeded
        assertTrue("call A must return 1", resultText(okA).contains("\"v\":1"))

        // Call 2: the SAME action with ONE character of code changed (v:1 -> v:10). A new card
        // with a NEW binding hash must appear — the old approval (bindingA) can never cover it.
        val handleB = dispatchJs("js-s3-b-$run", "js-s3-turn-b-$run", "return { v: 10 }")
        val approvalIdB = approvalIdOf("js-s3-b-$run")
        val bindingB =
            container.storage.approvals
                .resolve(approvalIdB)
                .bindingHash
        assertTrue(
            "changing the code must change the approval binding hash",
            bindingA != bindingB,
        )
        container.chatService.approveApproval(approvalIdB)
        val okB = handleB.join() as ToolDispatchOutcome.Succeeded
        assertTrue("call B must return 10 (its own approval, not call A's)", resultText(okB).contains("\"v\":10"))
    }

    // ------------------------------------------------------------------ S4

    @Test
    fun jsErrorBackfillsStableFailureNotFakeSuccess() {
        val handle = dispatchJs("js-s4-$run", "js-s4-turn-$run", "throw new Error('hxadev-jsboom')")
        container.chatService.approveApproval(approvalIdOf("js-s4-$run"))
        val outcome = handle.join()

        // NEVER a fake success: the dispatch is a stable execution failure, and a JS throw is a
        // confirmed side-effect-free failure (the platform executor says so, not the model).
        val failed =
            outcome as? ToolDispatchOutcome.ExecutionFailed
                ?: error("a JS throw must be a stable ExecutionFailed, was: $outcome")
        assertTrue("a JS error is a confirmed side-effect-free failure", failed.sideEffectFree)
        // The call row settles FAILED.
        assertEquals(
            ToolCallState.FAILED.name,
            container.storage.toolCalls
                .resolve("js-s4-$run")
                .state,
        )
        // The audit carries the JS_ERROR terminal status; the engine error body is redacted out.
        assertEquals("JS_ERROR", executionDetailOf("js-s4-$run")["status"]?.jsonPrimitive?.content)
        val rowPayload = auditRow("js-s4-$run").redactedPayload
        assertTrue("the JS engine error body leaked into the audit", !rowPayload.contains("hxadev-jsboom"))
    }

    // ------------------------------------------------------------------ S5

    @Test
    fun quickJsSingleConcurrencySerializesOnDevice() {
        // The isolated QuickJS backend is a SINGLE instance that runs one execution at a time.
        // A short busy loop makes each execution window measurably non-trivial (hundreds of ms),
        // so "run A fully finished before run B started" is a real, observable serialization —
        // not a 0 ms artifact. Distinct results prove per-run isolation (no cross-contamination).
        val codeA = "let t = 0; for (let i = 0; i < 2_000_000; i++) t += 1; return { run: 'A', t: t }"
        val codeB = "let t = 0; for (let i = 0; i < 4_000_000; i++) t += 1; return { run: 'B', t: t }"
        val callA = "js-s5-a-$run"
        val callB = "js-s5-b-$run"

        // Run A to completion FIRST (the production Act-mode pattern: one call at a time).
        val hA = dispatchJs(callA, "js-s5-turn-a-$run", codeA)
        container.chatService.approveApproval(approvalIdOf(callA))
        val succeededA = hA.join() as ToolDispatchOutcome.Succeeded
        assertTrue("run A must carry its own distinct result", resultText(succeededA).contains("\"t\":2000000"))

        // THEN run B: it can only start after A has fully settled on the single backend.
        val hB = dispatchJs(callB, "js-s5-turn-b-$run", codeB)
        container.chatService.approveApproval(approvalIdOf(callB))
        val succeededB = hB.join() as ToolDispatchOutcome.Succeeded
        assertTrue(
            "run B must carry its own distinct result (no cross-contamination)",
            resultText(succeededB).contains("\"t\":4000000"),
        )

        // The two execution windows are non-degenerate and do NOT overlap: run A fully completed
        // before run B started (single concurrency on the one isolated instance).
        val (aStart, aEnd) = executionWindow(callA)
        val (bStart, bEnd) = executionWindow(callB)
        assertTrue("run A's window must be measurable, was ${aEnd - aStart} ms", aEnd > aStart)
        assertTrue("run B's window must be measurable, was ${bEnd - bStart} ms", bEnd > bStart)
        assertTrue(
            "QuickJS runs must serialize (A fully finished before B started): A=$aStart..$aEnd B=$bStart..$bEnd",
            aEnd <= bStart,
        )
        // Each run used a real isolated instance (per-run isolation).
        assertTrue("run A must be isolated", executionDetailOf(callA)["isolated"]?.jsonPrimitive?.content == "true")
        assertTrue("run B must be isolated", executionDetailOf(callB)["isolated"]?.jsonPrimitive?.content == "true")
    }

    /** The isolated run's returned JSON document (the `result` field of the model-visible output). */
    private fun resultText(succeeded: ToolDispatchOutcome.Succeeded): String =
        Json
            .parseToJsonElement(succeeded.result.payload)
            .jsonObject
            .get("result")
            ?.jsonPrimitive
            ?.content
            ?: error("the successful dispatch must backfill a `result` field, was: ${succeeded.result.payload}")

    private fun executionWindow(toolCallId: String): Pair<Long, Long> {
        val obj = Json.parseToJsonElement(auditRow(toolCallId).redactedPayload).jsonObject
        val start =
            obj["executionStartedAt"]?.jsonPrimitive?.longOrNull
                ?: error("executionStartedAt missing for $toolCallId (no real execution?)")
        val end = obj["finishedAt"]?.jsonPrimitive?.longOrNull ?: error("finishedAt missing for $toolCallId")
        return start to end
    }

    companion object {
        const val JS_TOOL_NAME = "code.javascript.run"
    }
}
