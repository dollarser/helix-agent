package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.chat.ChatService
import com.helix.app.chat.ToolTimelineRow
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
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
import java.util.concurrent.atomic.AtomicInteger
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
        // Seed the session row BEFORE opening it (the test seeds turns per-test, but the
        // session itself must exist when it is opened), and open it: since HXA-048 the
        // chat timeline is scoped to the OPEN session, and a different session can be
        // left open by other test classes (the app process survives across test classes
        // within one instrumentation run). Without this, the live pending-card rows are
        // filtered out of every `screen.value.toolTimeline` read here — order-dependent
        // and invisible in isolated runs.
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "flow session", null, null, now)
        }
        container.chatService.openSession(sessionId)
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

    @After
    fun settleAbandonedApprovals() {
        // Backstop: a test that died mid-approval (any assertion before its deny/approve)
        // leaves its dispatch BLOCKED in the broker, holding an EXCLUSIVE scheduler slot
        // (every non-read call is a full barrier) for the process lifetime — every later
        // dispatch in this process would then wait on admission forever (no approval
        // record, no card: the "no pending approval record" cascade seen in the full
        // developer suite). Cancel any approval still pending on this class's seeded
        // session and wait for its dispatch to settle (CANCELLED) so the slot is free
        // before the next test starts.
        pendingApprovalIdsOn(sessionId).forEach { container.toolPipeline.broker.cancel(it) }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && pendingApprovalIdsOn(sessionId).isNotEmpty()) {
            Thread.sleep(50)
        }
        // A failed assertion after the developer-only switch must not leak ADVANCED into
        // the next test instance and change its policy path.
        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE &&
            container.profileStore.profile != SafetyProfile.STANDARD
        ) {
            container.profileStore.switchTo(SafetyProfile.STANDARD)
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

    /**
     * Polls the storage-backed approval record — the source of truth for the pending
     * decision — until the broker has created it. The old UI-timeline probe read the
     * card off `screen.value.toolTimeline`, which is scoped to the open session (HXA-048)
     * and refreshed asynchronously: when another test class left a different session open,
     * the card row was filtered out and this timed out (the order-dependent "no pending
     * card" flake). The record exists the moment the broker starts waiting (before the
     * card is even published) and is independent of UI state.
     */
    private fun approvalIdOf(toolCallId: String): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            container.storage.approvals
                .byToolCall(toolCallId)
                ?.let { return it.id }
            Thread.sleep(50)
        }
        error("no pending approval record for $toolCallId")
    }

    /**
     * The LIVE approval-card row for [toolCallId]. A durable call row can become visible
     * before the broker attaches its card, so wait for both facts instead of returning the
     * first matching timeline row and racing the card publication under full-suite load.
     */
    private fun awaitTimelineRow(toolCallId: String): ToolTimelineRow {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            container.chatService.screen.value.toolTimeline
                .firstOrNull { it.callId == toolCallId && it.card != null }
                ?.let { return it }
            Thread.sleep(50)
        }
        error("no live approval-card row for $toolCallId")
    }

    @Test
    fun profileSwitchDoesNotChangePendingDecisionOnDevice() {
        // Dispatch under the build's start profile; the real broker publishes the card +
        // the PENDING record.
        val handle = dispatchOnThread("flow-call-1-$run", "flow-turn-1-$run")
        val approvalId = approvalIdOf("flow-call-1-$run")
        val pendingRecord = container.storage.approvals.resolve(approvalId)
        assertNull("the record must still be pending", pendingRecord.decision)
        val hashBefore = pendingRecord.bindingHash

        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            // Developer build: the switch SUCCEEDS — and must STILL not change the pending
            // decision: the binding has no profile field and the card keeps the facts
            // captured at request time (a later switch never rewrites a pending card).
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            assertEquals(SafetyProfile.ADVANCED, container.profileStore.profile)
        } else {
            // Consumer store refuses ADVANCED outright (HXA-028 / ADR-0005): the profile
            // source cannot even move.
            assertThrows(IllegalArgumentException::class.java) {
                container.profileStore.switchTo(SafetyProfile.ADVANCED)
            }
            assertEquals(SafetyProfile.STANDARD, container.profileStore.profile)
        }

        // The pending record is untouched after the (refused or performed) switch:
        // same hash, still pending.
        val recordAfter = container.storage.approvals.resolve(approvalId)
        assertEquals(hashBefore, recordAfter.bindingHash)
        assertNull(recordAfter.decision)

        // The card on screen still shows the facts captured at request time.
        val card = awaitTimelineRow("flow-call-1-$run").card
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

        // A developer build now sits on ADVANCED: restore the default so later test
        // classes in the same process see the same start state.
        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            container.profileStore.switchTo(SafetyProfile.STANDARD)
        }
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
        // A persisted synthetic turn is not an active ChatService run. stop() deliberately
        // targets the owned active run; use the real model-loop fixture to establish
        // ownership. It asserts cancellation, zero execution, undecided/unconsumed proof
        // and late-card rejection.
        // HXA-209 B4: the HXA-200 three-state preference that forced the card is gone —
        // the `echo` tool the fixture model calls is LOCAL_MUTATION, so the platform
        // classifies it as an UNDETERMINED device mutation and the seeded READ_ONLY
        // session default resolves it to ASK through the ONE resolver (the same card
        // mechanism as every other approval). The two-phase restart/recovery protocol
        // (shared-preferences marker) is re-covered by the session-config recovery
        // tests; its marker's only consumer was removed with it.
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val container = (app as HelixApplication).appContainer
            val chat = container.chatService
            val executions = AtomicInteger()
            registerStopFixtureEcho(container, executions)
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createStopFixtureProvider(container, server.port)
                val session = chat.createSession("HXA209 stop fixture", provider, "fixture-model-a")
                val previous = chat.runControl.value
                try {
                    chat.openSession(session)
                    chat.setMode(AgentMode.ACT)
                    chat.send("Echo probe.")
                    stopAwait {
                        container.storage.turns.listBySession(session).any { turn ->
                            container.storage.toolCalls.listByTurn(turn.id).any {
                                container.storage.approvals.byToolCall(it.id) != null
                            }
                        }
                    }
                    stopAndAssertCancellation(container, chat, session, executions)
                } finally {
                    chat.stop()
                    stopAwait { !chat.screen.value.isSending }
                    chat.closeSession()
                    chat.setMode(previous.mode)
                    container.providerService.delete(provider)
                }
            }
        }
    }

    /**
     * The stop half of the fixture: stop the active run while the card is pending and
     * assert the HXA-200 stop contract — the turn and the blocked call settle CANCELLED,
     * the tool never executed, the proof stays undecided and unconsumed, and the old
     * card can never mint.
     */
    private fun stopAndAssertCancellation(
        container: AppContainer,
        chat: ChatService,
        session: String,
        executions: AtomicInteger,
    ) {
        val turn =
            container.storage.turns
                .listBySession(session)
                .single()
        val call =
            container.storage.toolCalls
                .listByTurn(turn.id)
                .single()
        val approval = requireNotNull(container.storage.approvals.byToolCall(call.id))
        chat.stop()
        stopAwait {
            container.storage.turns
                .resolve(turn.id)
                .state == "CANCELLED" &&
                !chat.screen.value.isSending
        }
        assertEquals(
            "CANCELLED",
            container.storage.toolCalls
                .resolve(call.id)
                .state,
        )
        assertEquals(0, executions.get())
        assertThrows(IllegalArgumentException::class.java) {
            container.toolPipeline.broker.decide(approval.id, ApprovalDecision.APPROVED)
        }
        val stoppedApproval = container.storage.approvals.resolve(approval.id)
        assertNull(stoppedApproval.decision)
        assertNull(stoppedApproval.consumedAt)
    }

    /** Polls [condition] every 25 ms for up to 30 s (the HXA-200 fixture's await, kept local). */
    private fun stopAwait(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("production state must settle", condition())
    }

    /**
     * Registers a fresh version of the `echo` tool the OPENAI_LISTED fixture model always
     * calls. LOCAL_MUTATION (not READ_ONLY) on purpose: the session-permission stage
     * classifies it as an UNDETERMINED device mutation, which the seeded READ_ONLY
     * default resolves to ASK — a card, with no preference to set (HXA-209 B4).
     */
    private fun registerStopFixtureEcho(
        container: AppContainer,
        executions: AtomicInteger,
    ) {
        val nextVersion =
            (
                container.toolPipeline.registry
                    .resolveLatest(ToolName("echo"))
                    ?.version
                    ?.value ?: 0
            ) + 1
        val descriptor =
            ToolDescriptor(
                name = ToolName("echo"),
                version =
                    com.helix.core.model
                        .ToolVersion(nextVersion),
                description = "Stop fixture",
                inputSchema = JsonObject(emptyMap()),
                outputSchema = JsonObject(emptyMap()),
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L2,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        container.toolPipeline.registry.register(descriptor)
        container.toolPipeline.implementations.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    return ToolExecutorResult.Completed(JsonObject(emptyMap()))
                }
            },
        )
    }

    /** The local loopback provider the stop fixture sends through (no account, no network). */
    private suspend fun createStopFixtureProvider(
        container: AppContainer,
        port: Int,
    ): String {
        val service = container.providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "HXA209 local stop fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        val probe = service.runConnectionTest(id)
        assertTrue("local fixture probe: $probe", probe is ProbeOutcome.Ok)
        val capability = service.runCapabilityTest(id)
        assertTrue("local capability probe: $capability", capability is ProbeOutcome.Ok)
        return id
    }

    companion object {
        const val FLOW_TOOL_NAME = "hxatest.flowrow"
    }
}
