package com.helix.app.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.RiskLevel
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.SessionPermissionConfig
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-192 device acceptance: the 209 session-authorization linkage for a plan session — a session
 * whose plan has been approved. The linkage proves the plan's APPROVAL neither mints a tool proof
 * nor pre-authorizes tools: the plan is a lifecycle decision, and every tool call the session later
 * runs is gated by the ONE 209 session-permission resolver exactly as if no plan had been approved.
 *
 * - approving a plan mints NO tool proof: it creates no tool call and therefore no approval card —
 *   the proof is the plan's APPROVED state + the PlanExecutionBinding + the audit trail, not a tool
 *   approval;
 * - a plan session under the FULL_ACCESS preset runs a mutation card-free (Succeeded, no card);
 * - a plan session under READ_ONLY still ASKs for a mutation (an approval card is created — the plan
 *   approval did not auto-allow it) and a denial stops it;
 * - a plan session with the tool DISABLED refuses it with TOOL_DISABLED even under FULL_ACCESS —
 *   the plan approval does not override the two-state availability.
 *
 * Operation-rule DENY is covered at the resolver level by the HXA-209 delivery (SessionPermission-
 * DeviceTest); the plan linkage here is that the plan approval changes none of these outcomes. Every
 * test asserts BOTH the side effect (did the tool run? was a card created? what is the durable call
 * state?) and the availability decision. Driven through the production per-call entry
 * ChatService.dispatchToolCall (real Room + storage-backed broker + real dispatcher), reusing
 * PlanReviewService / StoragePlanReviewPort for the plan decision (no new Plan Engine).
 */
@RunWith(AndroidJUnit4::class)
class PlanAuthorizationLinkageDeviceTest {
    private lateinit var container: AppContainer

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "pa-session-$run"
    private val toolName = ToolName(PLAN_AUTH_TOOL_NAME)

    /** The canonical trusted identity the execution entry keys the availability on (ADR 1.1). */
    private val sourceRef = ToolOrigin.BuiltInOrigin.canonicalOf()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        // The dispatch is scoped to the turn's session: seed the session row so the turn foreign key
        // holds and the open-session timeline scoping cannot hide our rows.
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "plan authorization fixture", null, null, now)
        }
        container.chatService.openSession(sessionId)
    }

    @After
    fun settleAbandonedApprovals() {
        // Backstop (same as SessionPermissionDeviceTest): a test that died mid-approval leaves its
        // dispatch BLOCKED in the broker, holding an exclusive scheduler slot for the process
        // lifetime. Cancel any approval still pending on this class's session before the next test.
        pendingApprovalIdsOn(sessionId).forEach { container.toolPipeline.broker.cancel(it) }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && pendingApprovalIdsOn(sessionId).isNotEmpty()) {
            Thread.sleep(50)
        }
    }

    @Test
    fun approvingAPlanMintsNoToolProof() {
        val planId = "plan-auth-proof-$run"
        seedReadyPlan(planId)
        val binding = planReviewService().approve(planId)
        // The plan is APPROVED and the binding is version-accurate — that is the proof of approval.
        val planEntity = container.storage.plans.resolveEntity(planId)
        assertEquals("APPROVED", planEntity.state)
        assertEquals(planId, binding.planId.value)
        assertEquals(planEntity.version, binding.planVersion)
        assertEquals(planEntity.hash, binding.planHash.hex)
        // The approval minted NO tool proof: it created no tool call and therefore no approval card.
        assertTrue(
            "a plan approval is a lifecycle decision, not a tool dispatch",
            toolCallsOn(sessionId).isEmpty(),
        )
        assertTrue("no tool call means no approval card was minted", approvalsOn(sessionId).isEmpty())
    }

    @Test
    fun aPlanSessionUnderFullAccessRunsAMutationCardFree() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        approveAPlanInSession()
        // The FULL_ACCESS preset governs the tool — the approved plan neither adds nor removes a card.
        saveConfig(SessionPermissionMode.FULL_ACCESS, System.currentTimeMillis())
        val outcome = dispatchOnThread("pa-call-full-$run", "pa-turn-full-$run").join()
        assertTrue("FULL_ACCESS must AutoProceed card-free, got: $outcome", outcome is ToolDispatchOutcome.Succeeded)
        assertEquals("the authorized tool must actually execute", 1, executions.get())
        assertNull(
            "no approval card is created for a card-free execution",
            container.storage.approvals.byToolCall("pa-call-full-$run"),
        )
        assertEquals(
            ToolCallState.COMPLETED.name,
            container.storage.toolCalls
                .resolve("pa-call-full-$run")
                .state,
        )
    }

    @Test
    fun aPlanSessionUnderReadOnlyStillAsksForAMutation() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        approveAPlanInSession()
        // READ_ONLY resolves the fixture's device mutation to ASK; the approved plan does not change it.
        saveConfig(SessionPermissionMode.READ_ONLY, System.currentTimeMillis())
        val handle = dispatchOnThread("pa-call-ro-$run", "pa-turn-ro-$run")
        // An approval card IS created — the plan approval did not auto-allow the mutation.
        val approvalId = approvalIdOf("pa-call-ro-$run")
        container.chatService.denyApproval(approvalId)
        val outcome = handle.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, outcome.code)
        assertEquals(
            "READ_ONLY still ASKs a mutation even with an approved plan; a denial stops it",
            0,
            executions.get(),
        )
    }

    @Test
    fun aDisabledToolInAPlanSessionIsRefused() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        approveAPlanInSession()
        val now = System.currentTimeMillis()
        // FULL_ACCESS would execute the tool; the tool-level disable is orthogonal and the approved
        // plan does not override it.
        saveConfig(SessionPermissionMode.FULL_ACCESS, now)
        setToolDisabled(true, now)
        val outcome = dispatchOnThread("pa-call-dis-$run", "pa-turn-dis-$run").join() as ToolDispatchOutcome.Denied
        assertEquals(
            "the plan approval must not override the two-state availability",
            DispatchOutcomeCode.TOOL_DISABLED,
            outcome.code,
        )
        assertEquals("the disabled tool must never run", 0, executions.get())
        assertNull(
            "a disabled tool stops BEFORE any card is created",
            container.storage.approvals.byToolCall("pa-call-dis-$run"),
        )
        assertEquals(
            ToolCallState.DENIED.name,
            container.storage.toolCalls
                .resolve("pa-call-dis-$run")
                .state,
        )
    }

    // ---------------------------------------------------------------- plan decision

    private fun seedReadyPlan(planId: String) {
        val artifact =
            PlanArtifact(
                id = PlanId(planId),
                objective = "Plan authorization linkage test",
                assumptions = emptyList(),
                steps = listOf(PlanStep("step one", "do the thing")),
                acceptanceCriteria = listOf("the thing is done"),
                risks = emptyList(),
                version = 1,
            )
        container.storage.withTransaction { container.storage.plans.save(artifact, "READY", null) }
    }

    /** Approves a fresh plan in this session's world (a lifecycle decision, not a tool dispatch). */
    private fun approveAPlanInSession(): String {
        val planId = "plan-auth-$run-${System.nanoTime()}"
        seedReadyPlan(planId)
        planReviewService().approve(planId)
        return planId
    }

    private fun planReviewService(): PlanReviewService {
        var seq = 0L
        return PlanReviewService(
            StoragePlanReviewPort(container.storage, SystemClock(), { "pa-${System.nanoTime()}-${seq++}" }),
        )
    }

    // ---------------------------------------------------------------- 209 gate fixtures

    /** Sets the session's ACTIVE config to a preset (a user change, audited by the write service). */
    private fun saveConfig(
        mode: SessionPermissionMode,
        now: Long,
    ) {
        container.sessionPermissionEdit.saveSessionConfig(sessionId, SessionPermissionConfig.of(mode), now)
    }

    /** Disables the fixture tool in the SESSION scope of this session. */
    private fun setToolDisabled(
        disabled: Boolean,
        now: Long,
    ): Boolean =
        container.sessionPermissionEdit.setToolAvailability(
            sourceRef,
            toolName.value,
            ToolAvailabilityScope.SESSION,
            sessionId,
            disabled,
            now,
        )

    /**
     * Registers a FRESH version of the fixture tool whose executor increments [executions]. A
     * per-test version is required: the registry persists across test methods within one
     * instrumentation run. LOCAL MUTATION, risk L2, no egress — the MODE (not a fixed risk gate)
     * decides the outcome.
     */
    private fun registerFreshTool(executions: AtomicInteger): ToolDescriptor {
        val nextVersion =
            (
                container.toolPipeline.registry
                    .resolveLatest(toolName)
                    ?.version
                    ?.value ?: 0
            ) + 1
        val descriptor =
            ToolDescriptor(
                name = toolName,
                version = ToolVersion(nextVersion),
                description = "plan authorization device fixture",
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
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    return ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
                }
            },
        )
        return descriptor
    }

    private fun args() = buildJsonObject { put("path", "pa-fixture-path") }

    private fun ensureTurn(turnId: String) {
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "plan authorization fixture", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    /**
     * Runs one dispatch through the production per-call pipeline on a worker thread (the broker
     * blocks on a pending decision, so this must never run on the main instrumentation thread).
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
                            PLAN_AUTH_TOOL_NAME,
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
    }

    /** Polls the storage-backed approval record (the source of truth) until the broker creates it. */
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

    private fun toolCallsOn(sessionId: String) =
        container.storage.turns
            .listBySession(sessionId)
            .flatMap { container.storage.toolCalls.listByTurn(it.id) }

    private fun approvalsOn(sessionId: String) =
        toolCallsOn(sessionId).mapNotNull {
            container.storage.approvals
                .byToolCall(it.callId)
                ?.id
        }

    /** The approval ids still pending (AWAITING_APPROVAL calls) on this class's seeded session. */
    private fun pendingApprovalIdsOn(sessionId: String): List<String> =
        toolCallsOn(sessionId)
            .filter { it.state == ToolCallState.AWAITING_APPROVAL.name }
            .mapNotNull {
                container.storage.approvals
                    .byToolCall(it.callId)
                    ?.id
            }

    companion object {
        const val PLAN_AUTH_TOOL_NAME = "hxadev.planauth"
    }
}
