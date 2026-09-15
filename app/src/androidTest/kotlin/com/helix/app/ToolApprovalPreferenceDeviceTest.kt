package com.helix.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.ToolApprovalPreferenceService
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
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ToolBaselineIdentity
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
import java.io.File
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
 * - 新工具默认 ASK 的可信升级基线 (Gap 2): on real Room with a controllable version code (separate
 *   database — the production container's code is fixed at the APK's), an upgrade-introduced,
 *   unconfigured tool resolves to an ASK tagged NEW_DEFAULT; a real user choice overrides it, a
 *   reset returns to it, and the decision survives a close/reopen "restart" because it is a pure
 *   function of the persisted first-write-wins baseline (点1, 2026-09-15).
 * - 范围不匹配 + 外部来源同名碰撞 (Gap 3): a session-scoped ALLOW does not exempt a dispatch from
 *   ANOTHER session — the dispatcher re-resolves against the call's own persisted session (point 7)
 *   — and a preference is bound to the tool SOURCE: an external same-named tool never inherits the
 *   built-in's stored ALLOW.
 */
@RunWith(AndroidJUnit4::class)
class ToolApprovalPreferenceDeviceTest {
    private lateinit var container: AppContainer

    /** Per-run suffix: the device Room persists across runs — tool names and ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "apref-session"

    /** Every session this class may have seeded cards into — the @After sweep must settle all of them. */
    private val sweptSessions = mutableSetOf(sessionId)

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
        // still pending on any of this class's seeded sessions and wait for its dispatch to settle
        // so the slot is free before the next test starts.
        sweptSessions.forEach { sid ->
            pendingApprovalIdsOn(sid).forEach { container.toolPipeline.broker.cancel(it) }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && sweptSessions.any { pendingApprovalIdsOn(it).isNotEmpty() }) {
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
    private fun registerLowRiskTool(
        name: String,
        onExecute: () -> Unit = {},
    ): ToolDescriptor {
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
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    onExecute()
                    return ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
                }
            },
        )
        return descriptor
    }

    /** Seeds the turn row the tool_calls foreign keys require. */
    private fun ensureTurnIn(
        turnId: String,
        sid: String,
    ) {
        val now = System.currentTimeMillis()
        if (container.storage.turns
                .listBySession(sid)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sid, now)
        }
    }

    /** The stable tool source identity the dispatcher resolves preferences against. */
    private fun sourceRefOf(descriptor: ToolDescriptor): String = descriptor.origin.canonicalOf()

    /**
     * Runs one dispatch on a worker thread (the broker blocks on the user's decision). The
     * dispatcher trusts the turn's PERSISTED session, so [sid] only seeds the turn — the
     * dispatch itself carries whichever session the turn belongs to.
     */
    private fun dispatchOnThread(
        toolCallId: String,
        turnId: String,
        toolName: String,
        sid: String = sessionId,
    ): DispatchHandle {
        ensureTurnIn(turnId, sid)
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
    fun aGlobalAskRestrictsANarrowerSessionAllow() {
        // ADR-0052 point 5: the applicable outer ASK remains a restriction.
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
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
        val callId = "apref-askallow-call-$run"
        val handle = dispatchOnThread(callId, "apref-askallow-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        container.chatService.denyApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
        assertEquals(1, container.storage.approvals.countByToolCall(callId))
    }

    @Test
    fun denyingTheToolWhileApprovalIsPendingBlocksTheOldCard() {
        val executions =
            java.util.concurrent.atomic
                .AtomicInteger()
        val descriptor = registerLowRiskTool("apref.pendingdeny.$run") { executions.incrementAndGet() }
        val source = sourceRefOf(descriptor)
        val service = container.toolApprovalPreferenceService
        service.set(
            source,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ASK,
            null,
            System.currentTimeMillis(),
        )
        val callId = "apref-pendingdeny-call-$run"
        val handle = dispatchOnThread(callId, "apref-pendingdeny-turn-$run", descriptor.name.value)
        val approvalId = approvalIdOf(callId)
        service.set(
            source,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.DENY,
            null,
            System.currentTimeMillis(),
        )
        container.chatService.approveApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.PREFERENCE_DENIED, outcome.code)
        assertEquals(0, executions.get())
        assertNull(
            container.storage.approvals
                .byToolCall(callId)!!
                .consumedAt,
        )
        assertEquals(1, container.storage.approvals.countByToolCall(callId))
    }

    @Test
    fun aSessionAllowIsNotLeakedToADispatchInAnotherSession() {
        // HXA-200 Gap 3 (范围不匹配) end to end: a GLOBAL ASK plus a session-scoped ALLOW granted
        // to the seeded session. The dispatcher re-resolves against the call's own PERSISTED
        // session (point 7), so a dispatch from ANOTHER session sees the GLOBAL ASK — the
        // seeded session's ALLOW row is not applicable there — and the call takes a card. If the
        // row leaked across sessions, the other session would resolve Allow and go card-free.
        val descriptor = registerLowRiskTool("apref.crosssession.$run")
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
        // The other session: fresh, with its own turn (the dispatcher trusts the turn's session).
        val otherSession = "apref-other-$run"
        sweptSessions += otherSession
        container.storage.sessions.create(otherSession, "apref other session", null, null, System.currentTimeMillis())
        // The read seam confirms the other session's view first: the session ALLOW is not there.
        val effectiveOther =
            container.toolApprovalPreferenceService.effectiveFor(
                sourceRef,
                descriptor.name.value,
                contractHash,
                otherSession,
                null,
            )
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effectiveOther)
        val callId = "apref-crosssession-call-$run"
        val handle = dispatchOnThread(callId, "apref-crosssession-turn-$run", descriptor.name.value, otherSession)
        val approvalId = approvalIdOf(callId)
        container.chatService.denyApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
    }

    @Test
    fun aSameNamedToolFromAnotherSourceDoesNotInheritThePreference() {
        // HXA-200 Gap 3 (外部来源同名碰撞) on REAL Room through the PRODUCTION service: preference
        // identity is (sourceRef, toolName), never the bare name. An external source (MCP/A2A)
        // exposing the SAME tool name has NO record of its own — the built-in's stored ALLOW does
        // not authorize it, so it stays Unset and keeps its original policy handling.
        val descriptor = registerLowRiskTool("apref.collision.$run")
        val builtinRef = sourceRefOf(descriptor)
        val externalRef = "mcp:apref-collision-$run"
        val contractHash = descriptor.contractHash.hex
        container.toolApprovalPreferenceService.set(
            builtinRef,
            descriptor.name.value,
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            contractHash,
            System.currentTimeMillis(),
        )
        val builtinEffective =
            container.toolApprovalPreferenceService.effectiveFor(
                builtinRef,
                descriptor.name.value,
                contractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Allow, builtinEffective)
        val externalEffective =
            container.toolApprovalPreferenceService.effectiveFor(
                externalRef,
                descriptor.name.value,
                contractHash,
                sessionId,
                null,
            )
        assertEquals(EffectiveToolPreference.Unset, externalEffective)
    }

    @Test
    fun anUpgradeIntroducedToolDefaultsToNewDefaultUntilConfigured() {
        // HXA-200 Gap 2 (点1, 2026-09-15), on REAL Room with a controllable version code: the
        // production container's code is fixed at this APK's, so the founding-build -> upgrade
        // story is driven against a SEPARATE database through the same public repositories and the
        // SAME [ToolApprovalPreferenceService] the production container constructs. The baseline
        // is the ONLY source of the NEW_DEFAULT default — no model claim, no "empty record ==
        // new tool" inference.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "new-default-baseline-$run.db"
        val contentDir = File(context.cacheDir, "content-$name")
        context.deleteDatabase(name)
        contentDir.deleteRecursively()
        val storage = HelixStorage.open(context, name, contentDir)
        try {
            assertUpgradeBaselineLifecycle(storage)
            // "Restart" of the same build: close the database — the reopen below is a fresh
            // instance holding none of the in-memory state.
        } finally {
            storage.close()
        }
        // The persisted facts alone must reproduce the decision, and the first-write-wins
        // re-reconcile must NOT re-stamp the markers (firstSeen stays 2, founding stays 1).
        val reopened = HelixStorage.open(context, name, contentDir)
        try {
            val restarted = serviceOn(reopened, 2L)
            restarted.reconcile(baselineIdentities(), 3_000L)
            assertNewDefaultStillHolds(reopened, restarted)
        } finally {
            reopened.close()
        }
        context.deleteDatabase(name)
        contentDir.deleteRecursively()
    }

    /**
     * The founding-build -> upgrade -> configure -> reset story against one real database at
     * controllable version codes 1 -> 2 (every baseline write and read goes through Room).
     */
    private fun assertUpgradeBaselineLifecycle(storage: HelixStorage) {
        // Founding build (versionCode 1): the trusted startup path registers the bundled set.
        // firstSeen == founding == current, so a founding tool is OLD — a fresh install never
        // forces ASK on its own tools (既有工具 UNSET).
        val founding = serviceOn(storage, 1L)
        founding.reconcile(listOf(ToolBaselineIdentity("builtin", "apref.baseline.old.$run")), 1_000L)
        assertEquals(1L, storage.toolRegistrationBaseline.foundingVersionCode())
        assertEquals(EffectiveToolPreference.Unset, effectiveFrom(founding, "apref.baseline.old.$run"))
        // Upgrade to build 2: the trusted path re-registers the new build's set, which adds a tool
        // the founding build did not have. Its marker is stamped firstSeen=2.
        val upgraded = serviceOn(storage, 2L)
        upgraded.reconcile(baselineIdentities(), 2_000L)
        assertEquals(
            2L,
            storage.toolRegistrationBaseline.firstSeenVersionCode("builtin", "apref.baseline.new.$run"),
        )
        // The upgrade-introduced, unconfigured tool resolves to an ASK tagged NEW_DEFAULT...
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT),
            effectiveFrom(upgraded, "apref.baseline.new.$run"),
        )
        // ...while the founding tool stays Unset (OLD — the upgrade did not introduce it).
        assertEquals(EffectiveToolPreference.Unset, effectiveFrom(upgraded, "apref.baseline.old.$run"))
        // A real user choice overrides the new-tool default: a live ALLOW (bound to h1)...
        upgraded.set(
            "builtin",
            "apref.baseline.new.$run",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            "h1",
            3_000L,
        )
        assertEquals(EffectiveToolPreference.Allow, effectiveFrom(upgraded, "apref.baseline.new.$run"))
        // ...and removing that choice RESETS to the default (a delete, not a fourth state): while
        // the baseline still says "new in build 2", it is back to Ask(NEW_DEFAULT).
        upgraded.remove("builtin", "apref.baseline.new.$run", ToolApprovalPreferenceScope.GLOBAL, "")
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT),
            effectiveFrom(upgraded, "apref.baseline.new.$run"),
        )
    }

    /** The read bundle re-run after the close/reopen restart. */
    private fun assertNewDefaultStillHolds(
        storage: HelixStorage,
        service: ToolApprovalPreferenceService,
    ) {
        assertEquals(1L, storage.toolRegistrationBaseline.foundingVersionCode())
        assertEquals(
            2L,
            storage.toolRegistrationBaseline.firstSeenVersionCode("builtin", "apref.baseline.new.$run"),
        )
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT),
            effectiveFrom(service, "apref.baseline.new.$run"),
        )
        assertEquals(EffectiveToolPreference.Unset, effectiveFrom(service, "apref.baseline.old.$run"))
    }

    /** The tool identities of the test's fictional builds (founding tool + the upgrade's tool). */
    private fun baselineIdentities() =
        listOf(
            ToolBaselineIdentity("builtin", "apref.baseline.old.$run"),
            ToolBaselineIdentity("builtin", "apref.baseline.new.$run"),
        )

    /** The one read every assertion here makes: (builtin, tool, contract h1, the seeded session). */
    private fun effectiveFrom(
        service: ToolApprovalPreferenceService,
        toolName: String,
    ) = service.effectiveFor("builtin", toolName, "h1", sessionId, null)

    /** A preference service over a test-owned [storage] at a CONTROLLABLE app version code. */
    private fun serviceOn(
        storage: HelixStorage,
        currentVersionCode: Long,
    ) = ToolApprovalPreferenceService(
        storage.toolApprovalPreferences,
        storage.toolRegistrationBaseline,
        currentVersionCode,
    )
}
