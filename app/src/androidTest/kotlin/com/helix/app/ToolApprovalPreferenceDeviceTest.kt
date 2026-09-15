package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalReason
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
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-200 device acceptance (verification-matrix row `:app:connectedConsumerDebugAndroidTest`):
 * the user's standing tool-approval preferences (ADR-0052) resolve end to end against the
 * PRODUCTION pipeline — real Room, the real dispatcher with the preference seam wired (the app's
 * [com.helix.app.approval.ToolApprovalPreferenceService] is BOTH the dispatcher's live read source
 * and the ONLY write path), and the real broker. The clarified point 1 (2026-09-14) is the
 * load-bearing case; each case drives the production write path and the SAME dispatcher that
 * re-reads the store before the call starts (point 7):
 *
 * - 既有工具 UNSET 沿用原 Policy: an in-scope low-risk call with no stored preference proceeds
 *   CARD-FREE exactly as pre-feature. This also pins the new-tool default: an empty record (a
 *   brand-new tool, for which no trusted registration/upgrade baseline exists yet) is Unset —
 *   never a fabricated ASK and never judged from the model's own claim (点1).
 * - 明确 ASK 增加询问限制: an explicit ASK forces a per-call card on a tool the policy alone
 *   would resolve card-free, and the provenance is EXPLICIT (点3).
 * - 失效 ALLOW 回退 ASK: a stored ALLOW whose bound contract no longer matches the live descriptor
 *   falls back to a card tagged ALLOW_INVALIDATED — distinct from a fresh Unset, so the provenance
 *   survives (点6).
 * - 跨 scope 优先级 DENY > ASK > ALLOW: an outer DENY is authoritative — a narrower (session)
 *   ALLOW that is still live cannot override it, and the call is blocked (no card) at the
 *   execution boundary (点5).
 */
@RunWith(AndroidJUnit4::class)
class ToolApprovalPreferenceDeviceTest {
    private lateinit var container: AppContainer

    /** Per-run suffix: the device Room persists across runs — tool names and ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "apref-session"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "apref session", null, null, now)
        }
        container.chatService.openSession(sessionId)
    }

    @After
    fun settleAbandonedApprovals() {
        // A test that dies after its card is published (any assertion before its deny) leaves the
        // dispatch BLOCKED in the broker, holding a scheduler slot for the process lifetime — every
        // later dispatch in the process would then wait on admission forever. Cancel any approval
        // still pending on this class's seeded session and wait for its dispatch to settle so the
        // slot is free before the next test starts.
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

    /** A READ_ONLY L0 tool: in-scope under STANDARD it resolves to a policy Allow (card-free). */
    private fun registerLowRiskTool(name: String): ToolDescriptor {
        val descriptor =
            ToolDescriptor(
                name = ToolName(name),
                version = ToolVersion(1),
                description = "approval preference device test tool",
                inputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                outputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L0,
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
                override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                    ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
            },
        )
        return descriptor
    }

    /** Seeds the turn row the tool_calls foreign keys require (the session is seeded in [setUp]). */
    private fun ensureTurn(turnId: String) {
        val now = System.currentTimeMillis()
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    /** The stable tool source identity the dispatcher resolves preferences against. */
    private fun sourceRefOf(descriptor: ToolDescriptor): String = descriptor.origin.canonicalOf()

    /** Runs one dispatch on a worker thread (the broker blocks on the user's decision). */
    private fun dispatchOnThread(
        toolCallId: String,
        turnId: String,
        toolName: String,
    ): DispatchHandle {
        ensureTurn(turnId)
        val latch = CountDownLatch(1)
        val outcome = arrayOf<ToolDispatchOutcome?>(null)
        val error = arrayOf<Throwable?>(null)
        val t =
            Thread {
                try {
                    outcome[0] = container.chatService.dispatchToolCall(toolCallId, turnId, toolName, "{}")
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
    }

    /** Polls the storage-backed approval record — the source of truth for a pending card. */
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

    @Test
    fun anUnsetLowRiskToolIsCardFreeUnderProductionWiring() {
        val descriptor = registerLowRiskTool("apref.unset.$run")
        val sourceRef = sourceRefOf(descriptor)
        val contractHash = descriptor.contractHash.hex
        // The clarified point 1, and the new-tool default: an empty record — an existing unconfigured
        // tool, or a brand-new tool for which NO trusted registration/upgrade baseline exists yet —
        // is Unset, so it keeps its original (card-free) policy handling. It is never forced to ASK
        // and never judged from the model's own claim.
        val effective =
            container.toolApprovalPreferenceService.effectiveFor(
                sourceRef,
                descriptor.name.value,
                contractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Unset, effective)
        val callId = "apref-unset-call-$run"
        val outcome = dispatchOnThread(callId, "apref-unset-turn-$run", descriptor.name.value).join()
        assertTrue(
            "an unset in-scope low-risk call must proceed card-free: $outcome",
            outcome is ToolDispatchOutcome.Succeeded,
        )
        assertNull(
            "no card must be published for an unset card-free call",
            container.storage.approvals.byToolCall(callId),
        )
    }

    @Test
    fun anExplicitAskForcesACardOnALowRiskTool() {
        val descriptor = registerLowRiskTool("apref.ask.$run")
        val sourceRef = sourceRefOf(descriptor)
        val contractHash = descriptor.contractHash.hex
        // Explicit ASK: a user-imposed restriction that forces a per-call card, carrying EXPLICIT.
        container.toolApprovalPreferenceService.set(
            sourceRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ASK,
            null,
            System.currentTimeMillis(),
        )
        val effective =
            container.toolApprovalPreferenceService.effectiveFor(
                sourceRef,
                descriptor.name.value,
                contractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
        // The policy alone would resolve this L0 call card-free; the ASK forces a card instead.
        val callId = "apref-ask-call-$run"
        val handle = dispatchOnThread(callId, "apref-ask-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        container.chatService.denyApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
    }

    @Test
    fun anInvalidatedAllowFallsBackToACard() {
        val descriptor = registerLowRiskTool("apref.stale.$run")
        val sourceRef = sourceRefOf(descriptor)
        val liveContractHash = descriptor.contractHash.hex
        // A stored ALLOW bound to a contract that no longer matches the live descriptor ("the tool
        // changed since you allowed it"): the resolver drops the stale ALLOW and falls back to an
        // ASK tagged ALLOW_INVALIDATED — distinct from a fresh Unset, so the provenance survives.
        container.toolApprovalPreferenceService.set(
            sourceRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            "stale-contract-$run",
            System.currentTimeMillis(),
        )
        val effective =
            container.toolApprovalPreferenceService.effectiveFor(
                sourceRef,
                descriptor.name.value,
                liveContractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED), effective)
        // A matching ALLOW would be card-free; the STALE binding is the only reason this cards.
        val callId = "apref-stale-call-$run"
        val handle = dispatchOnThread(callId, "apref-stale-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        container.chatService.denyApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
    }

    @Test
    fun anOuterDenyIsNotOverriddenByANarrowerAllow() {
        val descriptor = registerLowRiskTool("apref.deny.$run")
        val sourceRef = sourceRefOf(descriptor)
        val contractHash = descriptor.contractHash.hex
        // GLOBAL DENY (authoritative at any applicable scope) plus a NARROWER session ALLOW that is
        // still live (its contract matches): it must NOT override the outer DENY (DENY > ASK > ALLOW).
        container.toolApprovalPreferenceService.set(
            sourceRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.DENY,
            null,
            System.currentTimeMillis(),
        )
        container.toolApprovalPreferenceService.set(
            sourceRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.SESSION,
            sessionId,
            ToolApprovalPreference.ALLOW,
            contractHash,
            System.currentTimeMillis(),
        )
        val effective =
            container.toolApprovalPreferenceService.effectiveFor(
                sourceRef,
                descriptor.name.value,
                contractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Deny, effective)
        // The call is blocked at the execution boundary: a PREFERENCE_DENIED denial, never a card.
        val callId = "apref-deny-call-$run"
        val outcome = dispatchOnThread(callId, "apref-deny-turn-$run", descriptor.name.value).join()
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.PREFERENCE_DENIED, denied.code)
        assertNull("a DENY block must not publish a card", container.storage.approvals.byToolCall(callId))
    }

    @Test
    fun aNarrowerSessionAllowOverridesAGlobalAskCardFree() {
        // HXA-200 Gap 1 emphasis (全局 ASK + 窄 scope ALLOW), end to end on the PRODUCTION pipeline:
        // a GLOBAL ASK (a standing "ask before this tool" restriction) plus a NARROWER session ALLOW
        // that is still live (its contract matches). DENY is the only cross-scope-authoritative
        // state; for ASK vs ALLOW the narrowest scope wins (point 5), so the session ALLOW preempts
        // the global ASK and this in-scope L0 call proceeds CARD-FREE — the direct opposite of
        // anOuterDenyIsNotOverriddenByANarrowerAllow.
        val descriptor = registerLowRiskTool("apref.askallow.$run")
        val sourceRef = sourceRefOf(descriptor)
        val contractHash = descriptor.contractHash.hex
        container.toolApprovalPreferenceService.set(
            sourceRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ASK,
            null,
            System.currentTimeMillis(),
        )
        container.toolApprovalPreferenceService.set(
            sourceRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.SESSION,
            sessionId,
            ToolApprovalPreference.ALLOW,
            contractHash,
            System.currentTimeMillis(),
        )
        val effective =
            container.toolApprovalPreferenceService.effectiveFor(
                sourceRef,
                descriptor.name.value,
                contractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Allow, effective)
        val callId = "apref-askallow-call-$run"
        val outcome = dispatchOnThread(callId, "apref-askallow-turn-$run", descriptor.name.value).join()
        assertTrue(
            "a session ALLOW over a global ASK must proceed card-free: $outcome",
            outcome is ToolDispatchOutcome.Succeeded,
        )
        assertNull(
            "no card may be published for a session-ALLOW-over-global-ASK call",
            container.storage.approvals.byToolCall(callId),
        )
    }
}
