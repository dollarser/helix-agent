package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.RiskLevel
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.OperationFootprint
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.SessionPermissionResolution
import com.helix.core.policy.SessionPermissionResolver
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
 * HXA-209 D8 device acceptance (row `:app:connectedConsumerDebugAndroidTest`): the ONE
 * session-permission resolver, the two-state tool availability, and the preset rule tables are
 * enforced at the REAL execution entry (production Room + the storage-backed broker + the real
 * dispatcher, driven through the chat service's per-call entry
 * [com.helix.app.chat.ChatService.dispatchToolCall]). Every test asserts BOTH the side-effect
 * facts (did the tool actually run? was a card created? what is the durable call state?) and the
 * availability decision — never the outcome code alone.
 *
 * - a tool-level disable stops the call BEFORE any card (code TOOL_DISABLED, DecisionSource.USER)
 *   even under FULL_ACCESS which would otherwise execute it: disable is orthogonal to the mode.
 * - re-enabling a tool returns it to the MODE gate (READ_ONLY re-ASKs a card) — it is neither
 *   auto-allowed nor still disabled; a mode switch never re-enables a disabled tool.
 * - FULL_ACCESS AutoProceeds card-free and actually executes (the authorized path).
 * - a preset and an unedited CUSTOM copy of that preset resolve identically through the ONE
 *   resolver, with multi-effect DENY > ASK > ALLOW and the rm-rule floor that still cards even
 *   under FULL_ACCESS.
 * - a stored CUSTOM draft is INERT until it is re-selected as the session mode.
 */
@RunWith(AndroidJUnit4::class)
class SessionPermissionDeviceTest {
    private lateinit var container: AppContainer

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "sp-session-$run"
    private val toolName = ToolName(SP_TOOL_NAME)

    /** The canonical trusted identity the execution entry keys the availability on (ADR 1.1). */
    private val sourceRef = ToolOrigin.BuiltInOrigin.canonicalOf()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        // The dispatch is scoped to the turn's session: seed the session row so the turn foreign
        // key holds and the open-session timeline scoping (HXA-048) cannot hide our rows.
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "session permission fixture", null, null, now)
        }
        container.chatService.openSession(sessionId)
    }

    @After
    fun settleAbandonedApprovals() {
        // Backstop (same as ApprovalFlowDeviceTest): a test that died mid-approval leaves its
        // dispatch BLOCKED in the broker, holding an exclusive scheduler slot for the process
        // lifetime. Cancel any approval still pending on this class's session and wait for it to
        // settle so the slot is free before the next test starts.
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

    /** Seeds the session + turn rows the tool_calls foreign keys require. */
    private fun ensureTurn(turnId: String) {
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "session permission fixture", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    /** Sets the session's ACTIVE config to a preset (a user change, audited by the write service). */
    private fun saveConfig(
        mode: SessionPermissionMode,
        now: Long,
    ) {
        container.sessionPermissionEdit.saveSessionConfig(sessionId, SessionPermissionConfig.of(mode), now)
    }

    /** Enables (false) or disables (true) the fixture tool in the SESSION scope of this session. */
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
     * instrumentation run, so a shared executor would capture an earlier test's counter. LOCAL
     * MUTATION, risk L2, no egress — the MODE (not a fixed risk gate) decides the outcome.
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
                description = "session permission device fixture",
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

    private fun args() = buildJsonObject { put("path", "sp-fixture-path") }

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
                            SP_TOOL_NAME,
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

    @Test
    fun aDisabledToolStopsBeforeAnyCardAtTheExecutionEntry() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        // FULL_ACCESS would execute the tool; the tool-level disable is orthogonal and wins.
        val now = System.currentTimeMillis()
        saveConfig(SessionPermissionMode.FULL_ACCESS, now)
        setToolDisabled(true, now)
        val outcome = dispatchOnThread("sp-call-1-$run", "sp-turn-1-$run").join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.TOOL_DISABLED, outcome.code)
        assertEquals("the disabled tool must never run", 0, executions.get())
        assertNull(
            "a disabled tool stops BEFORE any card is created",
            container.storage.approvals.byToolCall("sp-call-1-$run"),
        )
        assertEquals(
            ToolCallState.DENIED.name,
            container.storage.toolCalls
                .resolve("sp-call-1-$run")
                .state,
        )
    }

    @Test
    fun reEnablingAReturnedToolFallsBackToTheModeGate() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        val now = System.currentTimeMillis()
        // READ_ONLY resolves the fixture's undetermined device mutation to ASK.
        saveConfig(SessionPermissionMode.READ_ONLY, now)
        // Phase 1: disabled under READ_ONLY -> stops before the resolver (TOOL_DISABLED, not ASK).
        setToolDisabled(true, now)
        val disabled = dispatchOnThread("sp-call-2a-$run", "sp-turn-2-$run").join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.TOOL_DISABLED, disabled.code)
        assertEquals(0, executions.get())
        // Phase 2: re-enable -> the MODE gate (READ_ONLY) re-ASKs a card; neither auto-allowed nor
        // still disabled.
        assertTrue(
            "re-enabling must remove the stored disable row",
            setToolDisabled(false, now),
        )
        val h2 = dispatchOnThread("sp-call-2b-$run", "sp-turn-2-$run")
        val approvalId = approvalIdOf("sp-call-2b-$run")
        container.chatService.denyApproval(approvalId)
        val reEnabled = h2.join() as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, reEnabled.code)
        assertEquals("READ_ONLY re-ASKs (a card) rather than auto-allowing", 0, executions.get())
    }

    @Test
    fun fullAccessExecutesAReEnabledToolCardFree() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        // The tool is enabled by default on a fresh session, so the mode decides the outcome.
        saveConfig(SessionPermissionMode.FULL_ACCESS, System.currentTimeMillis())
        val outcome = dispatchOnThread("sp-call-3-$run", "sp-turn-3-$run").join()
        assertTrue("FULL_ACCESS must AutoProceed card-free, got: $outcome", outcome is ToolDispatchOutcome.Succeeded)
        assertEquals("the authorized tool must actually execute", 1, executions.get())
        assertNull(
            "no approval card is created for a card-free execution",
            container.storage.approvals.byToolCall("sp-call-3-$run"),
        )
        assertEquals(
            ToolCallState.COMPLETED.name,
            container.storage.toolCalls
                .resolve("sp-call-3-$run")
                .state,
        )
    }

    @Test
    fun aModeSwitchNeverReEnablesADisabledTool() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        val now = System.currentTimeMillis()
        saveConfig(SessionPermissionMode.READ_ONLY, now)
        setToolDisabled(true, now)
        // Switching to FULL_ACCESS must NOT revive the disabled tool.
        saveConfig(SessionPermissionMode.FULL_ACCESS, now + 1)
        assertEquals(
            SessionPermissionMode.FULL_ACCESS,
            container.sessionPermissionEdit.activeConfigFor(sessionId)?.mode,
        )
        val outcome = dispatchOnThread("sp-call-4-$run", "sp-turn-4-$run").join() as ToolDispatchOutcome.Denied
        assertEquals("the disable survives a mode switch", DispatchOutcomeCode.TOOL_DISABLED, outcome.code)
        assertEquals(0, executions.get())
    }

    @Test
    fun customDenyStopsAtTheExecutionEntryWithoutCardOrSideEffect() {
        val executions = AtomicInteger()
        registerFreshTool(executions)
        val rules =
            SessionPermissionConfig.copyPreset(SessionPermissionMode.FULL_ACCESS).toMutableMap().apply {
                put(OperationEffect.DEVICE_SYSTEM_MUTATION, OperationRule.DENY)
            }
        container.sessionPermissionEdit.saveSessionConfig(
            sessionId,
            SessionPermissionConfig.custom(rules),
            System.currentTimeMillis(),
        )
        val callId = "sp-custom-deny-$run"
        val outcome = dispatchOnThread(callId, "sp-custom-deny-turn-$run").join()
        assertTrue("Expected denial through production Dispatcher, got $outcome", outcome is ToolDispatchOutcome.Denied)
        assertEquals(0, executions.get())
        assertNull(container.storage.approvals.byToolCall(callId))
        assertEquals(
            ToolCallState.DENIED.name,
            container.storage.toolCalls
                .resolve(callId)
                .state,
        )
    }

    @Test
    fun presetsAndCustomResolveThroughOneResolverWithDenyDominating() {
        // A CUSTOM table copied from WORKSPACE: the workspace-write tightened to DENY, command
        // execution held at ASK (the workspace read stays ALLOW from the preset).
        val customRules =
            SessionPermissionConfig.copyPreset(SessionPermissionMode.WORKSPACE).toMutableMap().apply {
                put(OperationEffect.FILE_MUTATION_WORKSPACE, OperationRule.DENY)
                put(OperationEffect.COMMAND_EXECUTION, OperationRule.ASK)
            }
        val custom = SessionPermissionConfig.custom(customRules)
        val mutationRead =
            OperationFootprint(
                effects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE, OperationEffect.FILE_READ_WORKSPACE),
            )
        val commandRead =
            OperationFootprint(
                effects = setOf(OperationEffect.COMMAND_EXECUTION, OperationEffect.FILE_READ_WORKSPACE),
            )
        val read = OperationFootprint(effects = setOf(OperationEffect.FILE_READ_WORKSPACE))
        // Multi-effect precedence: one DENY refuses the whole call even though the read is ALLOW.
        val mutationResolve = SessionPermissionResolver.resolve(custom, mutationRead, rmCommandHit = false)
        val commandResolve = SessionPermissionResolver.resolve(custom, commandRead, rmCommandHit = false)
        val readResolve = SessionPermissionResolver.resolve(custom, read, rmCommandHit = false)
        assertTrue(mutationResolve is SessionPermissionResolution.Denied)
        assertTrue(commandResolve is SessionPermissionResolution.RequiresApproval)
        assertTrue(readResolve is SessionPermissionResolution.AutoProceed)
        // Same-config semantics: an unedited CUSTOM copy of a preset resolves identically to the
        // preset itself (FILE_READ_EXTERNAL is ASK under WORKSPACE in both).
        val externalRead = OperationFootprint(effects = setOf(OperationEffect.FILE_READ_EXTERNAL))
        val presetCopy =
            SessionPermissionConfig.custom(SessionPermissionConfig.copyPreset(SessionPermissionMode.WORKSPACE))
        val fromPreset =
            SessionPermissionResolver.resolve(
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE),
                externalRead,
                rmCommandHit = false,
            )
        val fromCopy = SessionPermissionResolver.resolve(presetCopy, externalRead, rmCommandHit = false)
        assertTrue(fromPreset is SessionPermissionResolution.RequiresApproval)
        assertTrue(fromCopy is SessionPermissionResolution.RequiresApproval)
        // The rm-rule floor: an rm hit still cards even under FULL_ACCESS (which otherwise allows).
        val command = OperationFootprint(effects = setOf(OperationEffect.COMMAND_EXECUTION))
        val fullAccess = SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS)
        val noRm = SessionPermissionResolver.resolve(fullAccess, command, rmCommandHit = false)
        val rmHit = SessionPermissionResolver.resolve(fullAccess, command, rmCommandHit = true)
        assertTrue(noRm is SessionPermissionResolution.AutoProceed)
        assertTrue(rmHit is SessionPermissionResolution.RequiresApproval)
    }

    @Test
    fun aStoredCustomDraftIsInertUntilReactivated() {
        val now = System.currentTimeMillis()
        saveConfig(SessionPermissionMode.WORKSPACE, now)
        val draftRules =
            SessionPermissionConfig.copyPreset(SessionPermissionMode.WORKSPACE).toMutableMap().apply {
                put(OperationEffect.FILE_MUTATION_WORKSPACE, OperationRule.ASK)
            }
        container.sessionPermissionEdit.saveCustomDraft(
            sessionId,
            SessionPermissionMode.WORKSPACE,
            draftRules,
            now,
        )
        // Inert: the session is on WORKSPACE (not CUSTOM), so the draft does not change what it runs.
        assertEquals(
            SessionPermissionMode.WORKSPACE,
            container.sessionPermissionEdit.activeConfigFor(sessionId)?.mode,
        )
        assertTrue("the draft must be stored", container.sessionPermissionEdit.customDraftFor(sessionId) != null)
        // Re-selecting CUSTOM applies the stored draft as the active config.
        assertTrue(container.sessionPermissionEdit.activateCustomDraft(sessionId, now + 1))
        val active = container.sessionPermissionEdit.activeConfigFor(sessionId)
        assertEquals(SessionPermissionMode.CUSTOM, active?.mode)
        assertEquals(draftRules, active?.rules)
    }

    companion object {
        const val SP_TOOL_NAME = "hxadev.sessionperm"
    }
}
