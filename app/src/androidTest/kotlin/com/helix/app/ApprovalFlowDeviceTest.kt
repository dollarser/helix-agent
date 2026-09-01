package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.ApprovalCancelledException
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.DispatchOutcomeCode
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-036 device acceptance (verification-matrix row `:app:connectedConsumerDebugAndroidTest`):
 * the production pipeline (real Room + the storage-backed broker + the real dispatcher,
 * driven through the chat service's per-call entry [com.helix.app.chat.ChatService.dispatchToolCall]
 * — persist the tool_call row with canonical args, dispatch, settle) enforces the mandated
 * invariants end to end, and the audit page's storage carries only redacted rows:
 *
 * - B1 切换 Profile 不改变待审批决定: a pending card (real approval record) is untouched
 *   by a profile-source change; on the consumer variant the store refuses ADVANCED
 *   outright (HXA-028 / ADR-0005) and the pending record's binding hash is invariant.
 * - B2 拒绝后同动作不重复弹卡: after a user denial, an identical re-dispatch in the same
 *   turn is rejected SAME_TURN_DENIED with NO second approval record (no second card);
 *   the audit rows for both dispatches are redacted (no argument body).
 * - B3 停止不改变待审批记录: a turn stop while the card is pending CANCELS the blocked
 *   dispatch (ApprovalCancelledException), leaves the record PENDING (no decision was
 *   made — it expires with its window and can never mint) and settles the call row with
 *   its durable CANCELLED outcome (doc 11: every queued call gets one).
 */
@RunWith(AndroidJUnit4::class)
class ApprovalFlowDeviceTest {
    private lateinit var container: AppContainer
    private lateinit var descriptor: ToolDescriptor
    private val toolName = ToolName(FLOW_TOOL_NAME)
    private val marker = "hxadev-marker-31d7"

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "flow-session"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        if (container.toolPipeline.registry.resolveLatest(toolName) == null) {
            descriptor =
                ToolDescriptor(
                    name = toolName,
                    version =
                        com.helix.core.model
                            .ToolVersion(1),
                    description = "device flow test tool",
                    inputSchema =
                        Json
                            .parseToJsonElement(
                                """{"type":"object","properties":{"path":{"type":"string"}},""" +
                                    """"required":["path"],"additionalProperties":false}""",
                            ).let { it as JsonObject },
                    outputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
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
        } else {
            descriptor = container.toolPipeline.registry.resolveLatest(toolName)!!
        }
    }

    private fun args() = buildJsonObject { put("path", marker) }

    /** Seeds the session + turn rows the tool_calls foreign keys require. */
    private fun ensureTurn(turnId: String) {
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "flow session", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    /**
     * Runs one dispatch through the production per-call pipeline on a worker thread (the
     * broker blocks on the user's decision, so this must never run on the test's main
     * instrumentation thread): persist the tool_call row (canonical args) -> dispatch ->
     * settle the persisted state + timeline.
     */
    private fun dispatchOnThread(
        toolCallId: String,
        turnId: String,
    ): DispatchHandle {
        ensureTurn(turnId)
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val error = arrayOf<Throwable?>(null)
        val t =
            Thread {
                try {
                    outcome[0] =
                        container.chatService.dispatchToolCall(
                            toolCallId,
                            turnId,
                            FLOW_TOOL_NAME,
                            args().toString(),
                        )
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
            assertTrue("dispatch must finish", latch.await(30, TimeUnit.SECONDS))
            error[0]?.let { throw it }
            return outcome[0] ?: error("no outcome")
        }

        fun joinError(): Throwable {
            val finished = latch.await(30, TimeUnit.SECONDS)
            assertTrue(
                "dispatch must finish (thread still blocked — pool exhausted or wait never ended)",
                finished,
            )
            return error[0] ?: error("no error was thrown")
        }
    }

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

    @Test
    fun profileSwitchDoesNotChangePendingDecisionOnDevice() {
        // Dispatch under STANDARD (the consumer store is STANDARD-pinned); the real
        // broker publishes the card + the PENDING record.
        val handle = dispatchOnThread("flow-call-1-$run", "flow-turn-1-$run")
        val approvalId = approvalIdOf("flow-call-1-$run")
        val pendingRecord = container.storage.approvals.resolve(approvalId)
        assertNull("the record must still be pending", pendingRecord.decision)
        val hashBefore = pendingRecord.bindingHash

        // The consumer store refuses ADVANCED (HXA-028 / ADR-0005): the profile source
        // cannot even move — and structurally the binding has no profile field, so the
        // pending decision is invariant regardless.
        assertThrows(IllegalArgumentException::class.java) {
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
        }
        assertEquals(SafetyProfile.STANDARD, container.profileStore.profile)

        // The pending record is untouched after the (refused) switch attempt: same hash,
        // still pending.
        val recordAfter = container.storage.approvals.resolve(approvalId)
        assertEquals(hashBefore, recordAfter.bindingHash)
        assertNull(recordAfter.decision)

        // The card on screen still shows the STANDARD facts captured at request time.
        val card =
            container.chatService.screen.value.toolTimeline
                .first { it.callId == "flow-call-1-$run" }
                .card
        assertNotNull("the card must be live (pending)", card)

        // Resolve with a denial: the decision lands on the exact record that was pending,
        // the dispatch terminalizes Denied, and the call row gets its durable DENIED state.
        container.chatService.denyApproval(approvalId)
        val denied = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, denied.code)
        val decided = container.storage.approvals.resolve(approvalId)
        assertEquals("DENIED", decided.decision)
        assertEquals(hashBefore, decided.bindingHash)
        val callRow = container.storage.toolCalls.resolve("flow-call-1-$run")
        assertEquals(ToolCallState.DENIED.name, callRow.state)
    }

    @Test
    fun deniedActionIsNotRepromptedOnDevice() {
        // Call 1: the card appears, the user denies.
        val h1 = dispatchOnThread("flow-call-a-$run", "flow-turn-2-$run")
        val approvalId = approvalIdOf("flow-call-a-$run")
        container.chatService.denyApproval(approvalId)
        val denied1 = h1.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, denied1.code)
        // Exactly one audit row for the dispatch; the correlationId is the TOOL CALL id
        // (the dispatch audit contract's per-call correlation, HXA-035).
        val rowsAfterDenial =
            container.storage.auditEvents
                .recent(1000)
                .count { it.correlationId == "flow-call-a-$run" }
        assertEquals("one audit row per dispatch", 1, rowsAfterDenial)

        // Call 2: identical action, same turn — rejected SAME_TURN_DENIED. NO second
        // approval record exists (no second card was published).
        val h2 = dispatchOnThread("flow-call-b-$run", "flow-turn-2-$run")
        val denied2 = h2.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.SAME_TURN_DENIED, denied2.code)
        val approvalRowB = container.storage.approvals.byToolCall("flow-call-b-$run")
        assertNull("no second approval record -> no second card", approvalRowB)
        // The timeline shows call-b's row WITHOUT a card, with the stable denial label.
        val rowB =
            container.chatService.screen.value.toolTimeline
                .first { it.callId == "flow-call-b-$run" }
        assertNull("the reprompted action must not carry a card", rowB.card)
        assertEquals("本回合已拒绝该动作", rowB.stateLabel)

        // REDACTION on real storage: the audit payloads of both dispatches are
        // allowlisted — the marker (an argument value) never appears in any audit row.
        val turnRows =
            container.storage.auditEvents
                .recent(1000)
                .filter { it.correlationId in setOf("flow-call-a-$run", "flow-call-b-$run") }
        assertEquals(2, turnRows.size)
        turnRows.forEach { row ->
            val payload = Json.parseToJsonElement(row.redactedPayload).jsonObject
            assertEquals(
                com.helix.app.approval.StorageAuditSink.PAYLOAD_KEYS,
                payload.keys,
            )
            assertTrue(
                "argument body leaked into the audit payload",
                !row.redactedPayload.contains(marker),
            )
        }
    }

    @Test
    fun stopWhilePendingCancelsTheDispatchOnDevice() {
        val handle = dispatchOnThread("flow-call-c-$run", "flow-turn-3-$run")
        val approvalId = approvalIdOf("flow-call-c-$run")
        // The user stops the turn while the card is pending (the production stop() path:
        // the broker's card-level cancel AND the turn-level cancel signal).
        container.chatService.stop()
        val thrown = handle.joinError()
        assertTrue(
            "the blocked dispatch must be cancelled, was: ${thrown::class.java.name}",
            thrown is ApprovalCancelledException,
        )
        // The record stays PENDING (no decision was made): it expires with its window and
        // can never mint — a stop is not a denial.
        val record = container.storage.approvals.resolve(approvalId)
        assertNull("a stop is not a decision", record.decision)
        // The call row got its durable CANCELLED outcome (doc 11: cancel leaves a durable
        // outcome for every queued call).
        val callRow = container.storage.toolCalls.resolve("flow-call-c-$run")
        assertEquals(ToolCallState.CANCELLED.name, callRow.state)
    }

    companion object {
        const val FLOW_TOOL_NAME = "hxatest.flowrow"
    }
}
