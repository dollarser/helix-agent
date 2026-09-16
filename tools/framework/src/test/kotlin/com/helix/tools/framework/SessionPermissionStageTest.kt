package com.helix.tools.framework

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolAvailabilityStates
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.GrantState
import com.helix.core.policy.OperationFootprint
import com.helix.core.policy.PolicyEngine
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.SessionPermissionSource
import com.helix.core.policy.ToolAvailabilitySource
import com.helix.core.policy.UserScope
import com.helix.core.policy.WorkspaceScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * HXA-209 B3 (ADR-PERMISSIONS-001 section 2): the session-permission + tool-availability
 * stage in the [ToolDispatcher] pipeline. Covers the order the ADR mandates — a refusal
 * (tool disable, DENY rule, undetermined-touching-DENY) stops BEFORE any card; an approval
 * requirement composes its precise reasons into the SAME per-call surface; a clean
 * all-ALLOW footprint proceeds card-free; the rm-rule floor forces a card in every mode;
 * and the pre-start recheck honors a config/availability change that landed in the queue.
 * HXA-209 B4: the stage is the SINGLE card driver — a wired, session-authorized call is
 * never stopped or re-asked by the historical L3 default denial or the risk/egress
 * approval (they stay risk signals and compose into the session's card), while hard-fact
 * denials stop in every wiring. The unwired dispatcher keeps the historical 1:1 policy
 * mapping (the legacy fail-closed path for non-session contexts).
 */
class SessionPermissionStageTest {
    private lateinit var clock: TestClock
    private lateinit var registry: ToolRegistry
    private lateinit var impls: ToolImplementationRegistry
    private lateinit var center: CapabilityCenter
    private lateinit var broker: TestBroker
    private lateinit var sink: RecordingSink

    @Before
    fun setUp() {
        clock = TestClock()
        registry = ToolRegistry()
        impls = ToolImplementationRegistry()
        center = CapabilityCenter(GrantingResolver(clock))
        broker = TestBroker()
        sink = RecordingSink()
    }

    // ------------------------------------------------------------------ availability

    @Test
    fun aDisabledToolStopsBeforeAnyCardWithTheBoundSessionAndWorkspace() {
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                ToolAvailabilityStates(session = ToolAvailabilityState.DISABLED),
            )
        val dispatcher =
            dispatcherWith(
                source,
                source,
                ScriptedClassifier(CallEffectClassification(OperationFootprint())),
            )
        val executor = registerL0Tool()
        val outcome = dispatcher.dispatch(request(scope = WorkspaceScope("ws-1")))
        val denied = outcome as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.TOOL_DISABLED, denied.code)
        assertEquals(0, broker.acquireCalls.size)
        assertEquals(0, executor.invocations)
        val event = sink.events.single()
        assertEquals(DecisionSource.USER, event.decisionSource)
        assertNull("a disable is refused before the stage evaluates anything", event.sessionPermissionEvaluated)
        // The live read is bound to the request's session and workspace scope (point 7).
        assertEquals(listOf(StateCall("session-1", "ws-1", "built-in", "fake")), source.statesCalls)
    }

    @Test
    fun anOuterDisableWinsOverANarrowerEnable() {
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                ToolAvailabilityStates(
                    global = ToolAvailabilityState.DISABLED,
                    session = ToolAvailabilityState.ENABLED,
                ),
            )
        val dispatcher =
            dispatcherWith(
                source,
                source,
                ScriptedClassifier(CallEffectClassification(OperationFootprint())),
            )
        registerL0Tool()
        val denied = dispatcher.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.TOOL_DISABLED, denied.code)
    }

    // ----------------------------------------------------------------------- denials

    @Test
    fun aDenyOnADeterminedEffectStopsWithoutCard() {
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.custom(mapOf(OperationEffect.FILE_MUTATION_EXTERNAL to OperationRule.DENY)),
            )
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_EXTERNAL)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        val denied = dispatcher.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.OPERATION_DENIED, denied.code)
        assertTrue(denied.detail, denied.detail.contains("HARD_DENIAL:FILE_MUTATION_EXTERNAL"))
        assertEquals(0, broker.acquireCalls.size)
        assertEquals(0, executor.invocations)
        val event = sink.events.single()
        assertEquals(DecisionSource.USER, event.decisionSource)
        val audit = checkNotNull(event.sessionPermissionEvaluated)
        assertEquals(SessionPermissionDecisionAudit.OUTCOME_DENIED, audit.outcome)
        assertEquals("CUSTOM", audit.mode)
        assertEquals(1, audit.configVersion)
        assertEquals("OPERATION_DENIED", audit.denyCode)
        assertEquals(listOf("FILE_MUTATION_EXTERNAL"), audit.effects)
    }

    @Test
    fun anUndeterminedEffectTouchingADenyIsAnExecutionDomainDenial() {
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.custom(mapOf(OperationEffect.FILE_READ_EXTERNAL to OperationRule.DENY)),
            )
        val classification =
            CallEffectClassification(
                OperationFootprint(undeterminedEffects = setOf(OperationEffect.FILE_READ_EXTERNAL)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        registerL0Tool()
        val denied = dispatcher.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.OPERATION_DENIED_DOMAIN, denied.code)
        assertTrue(denied.detail, denied.detail.contains("UNDETERMINED_EFFECT:FILE_READ_EXTERNAL"))
        val audit = checkNotNull(sink.events.single().sessionPermissionEvaluated)
        assertEquals("OPERATION_DENIED_DOMAIN", audit.denyCode)
        assertEquals(emptyList<String>(), audit.effects)
        assertEquals(listOf("FILE_READ_EXTERNAL"), audit.undeterminedEffects)
    }

    // ------------------------------------------------------------------------ cards

    @Test
    fun anAskOnACardFreeCallComposesOneCardWithTheRiskLevel() {
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE))
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_EXTERNAL)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executor.invocations)
        val card = broker.acquireCalls.single()
        assertTrue(
            card.confirmationDetail,
            card.confirmationDetail.contains("SCOPE_OUTSIDE:FILE_MUTATION_EXTERNAL"),
        )
        assertTrue(card.confirmationDetail, card.confirmationDetail.contains("RISK_LEVEL:"))
        val event = sink.events.single()
        assertEquals(DecisionSource.USER, event.decisionSource)
        val audit = checkNotNull(event.sessionPermissionEvaluated)
        assertEquals(SessionPermissionDecisionAudit.OUTCOME_REQUIRES_APPROVAL, audit.outcome)
        assertEquals("WORKSPACE", audit.mode)
        assertEquals(listOf("SCOPE_OUTSIDE:FILE_MUTATION_EXTERNAL"), audit.reasons)
    }

    @Test
    fun aPolicyApprovalComposesIntoTheSessionCardWithBothDetails() {
        // HXA-209 B4 contract: the session stage is the single card driver, but when the
        // historical risk/egress approval fires for the SAME call its detail composes into
        // the SAME card — the precise session reasons are never hidden by the policy text
        // (the B3 behavior of the policy detail replacing them is the documented change).
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY))
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_EXTERNAL)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        registerTool(
            TestFixtures.builtIn(name = "fake", baseRisk = RiskLevel.L2),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        val card = broker.acquireCalls.single()
        assertTrue(
            "the policy approval detail is kept in the composed card",
            card.confirmationDetail.contains("dynamic risk L2 requires per-call approval"),
        )
        assertTrue(
            "the precise session reasons compose into the SAME card",
            card.confirmationDetail.contains("SCOPE_OUTSIDE:FILE_MUTATION_EXTERNAL"),
        )
        assertTrue(card.confirmationDetail, card.confirmationDetail.contains("RISK_LEVEL:"))
        val audit = checkNotNull(sink.events.single().sessionPermissionEvaluated)
        assertEquals(listOf("SCOPE_OUTSIDE:FILE_MUTATION_EXTERNAL"), audit.reasons)
    }

    @Test
    fun aCleanAllAllowFootprintProceedsCardFree() {
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val classification =
            CallEffectClassification(
                OperationFootprint(
                    effects =
                        setOf(
                            OperationEffect.COMMAND_EXECUTION,
                            OperationEffect.REMOTE_BUSINESS_MUTATION,
                            OperationEffect.DEVICE_SYSTEM_MUTATION,
                        ),
                ),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executor.invocations)
        assertEquals(0, broker.acquireCalls.size)
        val audit = checkNotNull(sink.events.single().sessionPermissionEvaluated)
        assertEquals(SessionPermissionDecisionAudit.OUTCOME_AUTO_PROCEED, audit.outcome)
        assertEquals(emptyList<String>(), audit.reasons)
    }

    @Test
    fun anRmCommandHitForcesAPreciseCardEvenInFullAccess() {
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.COMMAND_EXECUTION)),
                rmCommandHit = true,
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executor.invocations)
        val card = broker.acquireCalls.single()
        assertTrue(card.confirmationDetail, card.confirmationDetail.contains("RM_COMMAND_RULE"))
        val audit = checkNotNull(sink.events.single().sessionPermissionEvaluated)
        assertEquals(true, audit.rmCommandHit)
        assertEquals(listOf("RM_COMMAND_RULE"), audit.reasons)
    }

    // ------------------------------------------------------------------- pre-start

    @Test
    fun aDenyThatLandsBeforeStartStopsTheQueuedCall() {
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE))
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        source.configFromStartGate =
            SessionPermissionConfig.custom(
                mapOf(OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY),
            )
        val denied = dispatcher.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.OPERATION_DENIED, denied.code)
        assertEquals(0, executor.invocations)
        assertEquals(0, broker.consumeCalls.size)
        val event = sink.events.single()
        assertNull(event.sessionPermissionAtStart)
        // The at-start recheck re-runs the stage: the stored evaluation is the tighter one.
        val audit = checkNotNull(event.sessionPermissionEvaluated)
        assertEquals(SessionPermissionDecisionAudit.OUTCOME_DENIED, audit.outcome)
    }

    @Test
    fun aDisableThatLandsBeforeStartStopsTheQueuedCall() {
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val dispatcher =
            dispatcherWith(
                source,
                source,
                ScriptedClassifier(CallEffectClassification(OperationFootprint())),
            )
        val executor = registerL0Tool()
        source.statesFromStartGate = ToolAvailabilityStates(global = ToolAvailabilityState.DISABLED)
        val denied = dispatcher.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.TOOL_DISABLED, denied.code)
        assertEquals(0, executor.invocations)
    }

    // ---------------------------------------------------------------------- inert

    @Test
    fun theStageIsInertWhenTheSeamsAreUnwired() {
        val dispatcher =
            ToolDispatcher(clock, registry, impls, center, PolicyEngine(clock), broker, sink, { emptySet() })
        val executor = registerL0Tool()
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executor.invocations)
        assertEquals(0, broker.acquireCalls.size)
        val event = sink.events.single()
        assertNull(event.sessionPermissionEvaluated)
        assertNull(event.sessionPermissionAtStart)
    }

    // ------------------------------- HXA-209 B4: the stage is the single card driver

    @Test
    fun aSessionAuthorizedL2CallProceedsCardFreeWithTheRiskStillAudited() {
        // B4: the historical risk approval (完全免确认不是仅 L2 豁免) never re-asks a call
        // the session mode authorized; the dynamic risk stays in the audit.
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_WORKSPACE)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor =
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) }
        registerTool(TestFixtures.builtIn(name = "fake", baseRisk = RiskLevel.L2), executor)
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executor.invocations)
        assertEquals(0, broker.acquireCalls.size)
        val event = sink.events.single()
        assertEquals(RiskLevel.L2, event.riskLevel)
        assertEquals(
            SessionPermissionDecisionAudit.OUTCOME_AUTO_PROCEED,
            checkNotNull(event.sessionPermissionEvaluated).outcome,
        )
    }

    @Test
    fun aBaseL3DenialIsDemotedForAWiredSessionAndStaysHardWhenUnwired() {
        // B4: the historical L3 default denial is a product default, not an unexecutable
        // fact — wired, a session-authorized L3 call proceeds (risk audited); unwired, the
        // historical hard denial keeps stopping (legacy fail-closed).
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val wired =
            dispatcherWith(
                source,
                source,
                ScriptedClassifier(CallEffectClassification(OperationFootprint())),
            )
        registerTool(
            TestFixtures.builtIn(name = "fake", baseRisk = RiskLevel.L3),
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) },
        )
        assertTrue(wired.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(RiskLevel.L3, sink.events.single().riskLevel)

        val unwired =
            ToolDispatcher(clock, registry, impls, center, PolicyEngine(clock), broker, sink, { emptySet() })
        val denied = unwired.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.POLICY_DENIED, denied.code)
        assertTrue(denied.detail, denied.detail.contains("L3_DEFAULT_DENY"))
        assertEquals(DecisionSource.POLICY, sink.events.last().decisionSource)
    }

    @Test
    fun aHardFactDenialStopsDespiteFullAccess() {
        // B4: only the historical L3 default is demoted — hard-fact denials (here the Plan
        // read-only boundary, ADR: the Chat/Plan boundaries are never relaxed by presets)
        // stop in every wiring, before the session stage evaluates anything.
        val source = ScriptedSessionPermission(SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS))
        val dispatcher =
            dispatcherWith(
                source,
                source,
                ScriptedClassifier(CallEffectClassification(OperationFootprint())),
            )
        val executor =
            CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) }
        registerTool(
            TestFixtures.builtIn(
                name = "fake",
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L0,
            ),
            executor,
        )
        val denied = dispatcher.dispatch(request().copy(mode = AgentMode.PLAN)) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.POLICY_DENIED, denied.code)
        assertTrue(denied.detail, denied.detail.contains("PLAN_MODE_NOT_READ_ONLY"))
        assertEquals(0, executor.invocations)
        assertEquals(0, broker.acquireCalls.size)
        assertEquals(DecisionSource.POLICY, sink.events.single().decisionSource)
        assertNull("a hard-fact denial stops before the session stage", sink.events.single().sessionPermissionEvaluated)
    }

    @Test
    fun aDenyAtTheStartGateAfterApprovalLeavesTheProofUnconsumed() {
        // Kept HXA-200 contract through the new seam: a refusal that lands between the card
        // and the effect start stops the call — the acquired proof is never consumed.
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE),
            )
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_EXTERNAL)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        source.configFromStartGate =
            SessionPermissionConfig.custom(
                mapOf(OperationEffect.FILE_MUTATION_EXTERNAL to OperationRule.DENY),
            )
        val denied = dispatcher.dispatch(request()) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.OPERATION_DENIED, denied.code)
        assertEquals(1, broker.acquireCalls.size)
        assertEquals("the refusal stops the start: the proof is never consumed", 0, broker.consumeCalls.size)
        assertEquals(0, executor.invocations)
        assertNull(sink.events.single().executionStartedAt)
    }

    @Test
    fun aNewAskAtTheStartGateAcquiresOneCardThenCommitsWithTheProof() {
        // Kept HXA-200 contract through the new seam: a NEW ASK that lands after a card-free
        // evaluation re-enters the SAME card path at the start gate — one card, one proof,
        // and the at-start recheck then sees the proof and commits without a second card.
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            )
        val classification =
            CallEffectClassification(
                OperationFootprint(effects = setOf(OperationEffect.FILE_MUTATION_EXTERNAL)),
            )
        val dispatcher = dispatcherWith(source, source, ScriptedClassifier(classification))
        val executor = registerL0Tool()
        source.configFromStartGate =
            SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE)
        broker.script(ApprovalAcquisition.Approved(proofFor("call-1")))
        assertTrue(dispatcher.dispatch(request()) is ToolDispatchOutcome.Succeeded)
        assertEquals(1, executor.invocations)
        assertEquals("exactly one card for the late ASK", 1, broker.acquireCalls.size)
        assertEquals(1, broker.consumeCalls.size)
        assertNotNull(
            "the at-start recheck re-ran the stage with the proof",
            sink.events.single().sessionPermissionAtStart,
        )
    }

    @Test
    fun aDenyAfterStartDoesNotCancelTheStartedCall() {
        // Kept HXA-200 contract through the new seam: the live re-read stops calls that
        // have not started; a config change landing AFTER the start commits never cancels
        // the running effect (already-started tasks stop on their own, ADR section 4).
        val source =
            ScriptedSessionPermission(
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            )
        val dispatcher =
            dispatcherWith(
                source,
                source,
                ScriptedClassifier(CallEffectClassification(OperationFootprint())),
            )
        registerL0Tool()
        val request =
            request().copy(
                onExecutionStarting = {
                    source.config =
                        SessionPermissionConfig.custom(
                            mapOf(OperationEffect.FILE_READ_WORKSPACE to OperationRule.DENY),
                        )
                },
            )
        assertTrue(dispatcher.dispatch(request) is ToolDispatchOutcome.Succeeded)
        assertEquals(0, broker.acquireCalls.size)
    }

    // -------------------------------------------------------------------- helpers

    /** A dispatcher with exactly the HXA-209 session seams wired. */
    private fun dispatcherWith(
        source: SessionPermissionSource?,
        availability: ToolAvailabilitySource?,
        classifier: ToolEffectClassifier?,
    ): ToolDispatcher =
        ToolDispatcher(
            clock,
            registry,
            impls,
            center,
            PolicyEngine(clock),
            broker,
            sink,
            { emptySet() },
            sessionPermissions = source,
            toolAvailability = availability,
            effectClassifier = classifier,
        )

    private fun registerL0Tool(): CaptureExecutor {
        val executor = CaptureExecutor { ToolExecutorResult.Completed(emptyObject()) }
        registerTool(TestFixtures.builtIn(name = "fake"), executor)
        return executor
    }

    private fun registerTool(
        d: ToolDescriptor,
        executor: ToolExecutor,
    ) {
        registry.register(d)
        impls.register(d, executor)
    }

    private fun request(scope: UserScope? = null): ToolDispatchRequest =
        ToolDispatchRequest(
            toolCallId = "call-1",
            turnId = "turn-1",
            sessionId = "session-1",
            toolName = ToolName("fake"),
            toolVersion = ToolVersion(1),
            args = Json.parseToJsonElement("{}").jsonObject,
            mode = AgentMode.ACT,
            profile = SafetyProfile.STANDARD,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = scope,
            uiToken = "ui:card:1",
        )

    private fun emptyObject(): JsonObject = Json.parseToJsonElement("{}").jsonObject

    private fun proofFor(toolCallId: String): ApprovalProof = ApprovalProof(toolCallId, "e".repeat(64))

    private class TestClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private class GrantingResolver(
        private val clock: Clock,
    ) : CapabilityResolver {
        override fun resolve(capability: Capability): CapabilityGrant =
            CapabilityGrant(
                capability = capability,
                state = GrantState.GRANTED,
                grantedBySystem = true,
                userScope = null,
                checkedAt = clock.now(),
            )
    }

    private class TestBroker : ApprovalBroker {
        val scripted = ArrayDeque<ApprovalAcquisition>()
        val acquireCalls = mutableListOf<ApprovalRequest>()
        val consumeCalls = mutableListOf<ApprovalProof>()

        fun script(vararg acquisitions: ApprovalAcquisition) {
            scripted += acquisitions
        }

        override fun acquire(request: ApprovalRequest): ApprovalAcquisition {
            acquireCalls += request
            check(scripted.isNotEmpty()) { "broker scripted empty" }
            return scripted.removeFirst()
        }

        override fun consume(proof: ApprovalProof) {
            consumeCalls += proof
        }

        override fun reMint(proof: ApprovalProof): ApprovalProof = proof
    }

    private class RecordingSink : AuditSink {
        val events = mutableListOf<DispatchAuditEvent>()

        override fun record(event: DispatchAuditEvent) {
            events += event
        }
    }

    private class CaptureExecutor(
        private val result: () -> ToolExecutorResult,
    ) : ToolExecutor {
        var invocations = 0
            private set

        override fun execute(call: ExecutableToolCall): ToolExecutorResult {
            invocations++
            return result()
        }
    }

    /**
     * Both new seams from ONE mutable fact holder. The pipeline reads each seam twice per
     * dispatch (policy stage, then the pre-start recheck): the *FromStartGate values — a
     * config/disable that lands while the call sat in the queue — answer from the SECOND read
     * on, the same stateful-source pattern the HXA-200 start-gate tests use.
     */
    private class ScriptedSessionPermission(
        @Volatile var config: SessionPermissionConfig,
        @Volatile var states: ToolAvailabilityStates = ToolAvailabilityStates(),
    ) : SessionPermissionSource,
        ToolAvailabilitySource {
        @Volatile var configFromStartGate: SessionPermissionConfig? = null

        @Volatile var statesFromStartGate: ToolAvailabilityStates? = null
        private val configReads = AtomicInteger()
        private val statesReads = AtomicInteger()

        val statesCalls = mutableListOf<StateCall>()

        override fun configFor(sessionId: String): SessionPermissionConfig =
            if (configReads.incrementAndGet() >= 2) configFromStartGate ?: config else config

        override fun statesFor(
            sourceRef: String,
            toolName: String,
            sessionId: String?,
            workspaceRef: String?,
        ): ToolAvailabilityStates {
            statesCalls += StateCall(sessionId, workspaceRef, sourceRef, toolName)
            return if (statesReads.incrementAndGet() >= 2) statesFromStartGate ?: states else states
        }
    }

    private class ScriptedClassifier(
        @Volatile var classification: CallEffectClassification,
    ) : ToolEffectClassifier {
        override fun classify(
            request: ToolDispatchRequest,
            descriptor: ToolDescriptor,
        ): CallEffectClassification = classification
    }
}

/** One recorded live availability read (the stage must bind session + workspace scope, point 7). */
private data class StateCall(
    val sessionId: String?,
    val workspaceRef: String?,
    val sourceRef: String,
    val toolName: String,
)
