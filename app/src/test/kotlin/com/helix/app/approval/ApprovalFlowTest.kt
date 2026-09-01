package com.helix.app.approval

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityResolver
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.GrantState
import com.helix.core.policy.PolicyEngine
import com.helix.tools.framework.ApprovalAcquisition
import com.helix.tools.framework.ApprovalBroker
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.AuditSink
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolDispatcher
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-036 (app, JVM): the two mandated flow invariants, exercised through the REAL
 * [ToolDispatcher] + [PolicyEngine] (the same wiring the app container uses, with the
 * storage-backed broker/sink replaced by fakes — the broker's storage path is covered by
 * the device tests):
 *
 * - B1 切换 Profile 不改变待审批决定: the approval binding contains no profile field
 *   (HXA-034/ADR-0005) — a STANDARD vs ADVANCED request for the SAME action produces the
 *   SAME binding hash, so a profile switch while a card is pending cannot change the
 *   pending decision, the pending record or the mint.
 * - B2 拒绝后同动作不重复弹卡: a denied action is fingerprinted for its turn; an
 *   identical re-dispatch in the same turn is rejected SAME_TURN_DENIED WITHOUT a second
 *   approval request (no second card); a materially changed action gets a fresh card.
 */
class ApprovalFlowTest {
    private lateinit var clock: FakeClock
    private lateinit var registry: ToolRegistry
    private lateinit var impls: ToolImplementationRegistry
    private lateinit var broker: HoldingBroker
    private lateinit var sink: RecordingSink
    private lateinit var dispatcher: ToolDispatcher
    private lateinit var l2Descriptor: ToolDescriptor

    @Before
    fun setUp() {
        clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        registry = ToolRegistry()
        impls = ToolImplementationRegistry()
        val center =
            CapabilityCenter(
                object : CapabilityResolver {
                    override fun resolve(capability: Capability): CapabilityGrant =
                        CapabilityGrant(
                            capability = capability,
                            state = GrantState.GRANTED,
                            grantedBySystem = true,
                            userScope = null,
                            checkedAt = clock.now(),
                        )
                },
            )
        broker = HoldingBroker()
        sink = RecordingSink()
        dispatcher = ToolDispatcher(clock, registry, impls, center, PolicyEngine(clock), broker, sink)
        l2Descriptor =
            ToolDescriptor(
                name = ToolName("fs.write"),
                version = ToolVersion(1),
                description = "writes a file",
                inputSchema = json("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
                outputSchema = json("""{"type":"object"}"""),
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L2,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.NON_IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        registry.register(l2Descriptor)
        impls.register(
            l2Descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                    ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
            },
        )
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).let { it as JsonObject }

    private fun request(
        toolCallId: String,
        turnId: String,
        args: JsonObject,
        profile: SafetyProfile,
    ): ToolDispatchRequest =
        ToolDispatchRequest(
            toolCallId = toolCallId,
            turnId = turnId,
            sessionId = "sess-1",
            toolName = l2Descriptor.name,
            toolVersion = l2Descriptor.version,
            args = args,
            mode = AgentMode.ACT,
            profile = profile,
            executionTarget = l2Descriptor.executionTarget,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = null,
            uiToken = "chat:$turnId",
            egress = null,
            originSeenInSession = true,
            lanScopes = emptySet(),
            overwritesExisting = false,
            codeOrCommandChanged = false,
            sourceBindingChanged = false,
            cancel = NoCancellation,
        )

    private val argsA: JsonObject
        get() = buildJsonObject { put("path", "/ws/a.txt") }

    private val argsB: JsonObject
        get() = buildJsonObject { put("path", "/ws/b.txt") }

    /** Runs the dispatch on a worker thread; it may block on a held approval. */
    private fun dispatchThread(request: ToolDispatchRequest): DispatchThread {
        val t = DispatchThread { dispatcher.dispatch(request) }
        t.start()
        return t
    }

    private fun join(t: DispatchThread): ToolDispatchOutcome {
        t.join(10_000)
        assertTrue("dispatch thread must finish", !t.isAlive)
        return t.outcome ?: error("dispatch returned nothing")
    }

    // ------------------------------------------------------------------ B1

    @Test
    fun profileSwitchDoesNotChangePendingDecision() {
        // Dispatch 1: STANDARD. The broker HOLDS the decision (the card is pending).
        val t1 = dispatchThread(request("call-1", "turn-1", argsA, SafetyProfile.STANDARD))
        broker.awaitPending(1)
        val pendingBinding = broker.acquireCalls.single().binding

        // The binding is structurally profile-blind (HXA-034 / ADR-0005 / security doc
        // section 7.3): the canonical binding JSON has exactly the nine trusted fact
        // fields and NO profile field — switching the profile cannot change the pending
        // decision, the pending record or the mint.
        val bindingKeys =
            Json.parseToJsonElement(pendingBinding.canonicalJson).let { it as JsonObject }.keys
        assertEquals(
            setOf(
                "argsHash",
                "executionTarget",
                "scopeRef",
                "schemaHash",
                "sessionId",
                "toolCallId",
                "toolName",
                "toolVersion",
                "uiToken",
            ),
            bindingKeys,
        )

        // The profile "switches" to ADVANCED while call-1's card is still pending: the
        // same action is requested again (a new turn). The profile switch changes
        // nothing about call-1's pending decision: its record/binding is untouched, the
        // second dispatch is an independent pending, and L2 under ADVANCED still requires
        // per-call approval (no auto-approve in either profile).
        val t2 = dispatchThread(request("call-2", "turn-2", argsA, SafetyProfile.ADVANCED))
        broker.awaitPending(2)
        val binding2 = broker.acquireCalls[1].binding
        assertEquals(pendingBinding.toolName, binding2.toolName)
        assertEquals(pendingBinding.toolVersion, binding2.toolVersion)
        assertEquals(pendingBinding.argsHash, binding2.argsHash)
        assertEquals(pendingBinding.schemaHash, binding2.schemaHash)
        assertEquals(pendingBinding.scopeRef, binding2.scopeRef)
        assertEquals(pendingBinding.executionTarget, binding2.executionTarget)
        assertEquals(pendingBinding.sessionId, binding2.sessionId)
        assertEquals(
            pendingBinding.hash,
            broker.acquireCalls
                .single { it.binding == pendingBinding }
                .binding.hash,
        )

        // Resolve each pending independently (FIFO): call-1 DENIED, call-2 APPROVED.
        broker.releaseNext(ApprovalAcquisition.Denied)
        broker.releaseNext(
            ApprovalAcquisition.Approved(ApprovalProof("approval-2", "e".repeat(64))),
        )
        val denied1 = join(t1) as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, denied1.code)
        assertTrue(join(t2) is ToolDispatchOutcome.Succeeded)

        // The denial was recorded on call-1's exact (STANDARD-era) binding; call-2's
        // proof was consumed exactly once; call-1's proof was never minted.
        val auditDenial = sink.events.first { it.code == DispatchOutcomeCode.APPROVAL_DENIED }
        assertEquals(pendingBinding.hash, auditDenial.bindingHash)
        assertEquals(RiskLevel.L2, auditDenial.riskLevel)
        assertEquals(1, broker.consumeCalls.size)
    }

    // ------------------------------------------------------------------ B2

    @Test
    fun deniedActionIsNotRepromptedInTheSameTurn() {
        // First dispatch of the action: the card is shown (one approval request), the
        // user denies.
        broker.scriptNext(ApprovalAcquisition.Denied)
        val outcome1 = dispatcher.dispatch(request("call-1", "turn-1", argsA, SafetyProfile.STANDARD))
        val denied1 = outcome1 as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.APPROVAL_DENIED, denied1.code)
        assertEquals(1, broker.acquireCalls.size)
        assertEquals(1, sink.events.size)
        assertEquals(0, broker.consumeCalls.size)

        // Identical action, SAME turn: rejected at the dispatcher's same-turn gate —
        // NO second approval request, therefore NO second card.
        val outcome2 = dispatcher.dispatch(request("call-2", "turn-1", argsA, SafetyProfile.STANDARD))
        val denied2 = outcome2 as ToolDispatchOutcome.Denied
        assertEquals(DispatchOutcomeCode.SAME_TURN_DENIED, denied2.code)
        assertEquals("exactly one card for the same turn", 1, broker.acquireCalls.size)
        assertEquals(2, sink.events.size)
        assertEquals(DispatchOutcomeCode.SAME_TURN_DENIED, sink.events[1].code)

        // Materially changed arguments (different path) are a different action: a fresh
        // card is allowed and can be approved.
        broker.scriptNext(
            ApprovalAcquisition.Approved(ApprovalProof("approval-fake", "e".repeat(64))),
        )
        val outcome3 = dispatcher.dispatch(request("call-3", "turn-1", argsB, SafetyProfile.STANDARD))
        assertTrue(outcome3 is ToolDispatchOutcome.Succeeded)
        assertEquals(2, broker.acquireCalls.size)
        assertEquals(1, broker.consumeCalls.size)
    }

    @Test
    fun aNewTurnMayRepromptTheSameDeniedAction() {
        broker.scriptNext(ApprovalAcquisition.Denied)
        dispatcher.dispatch(request("call-1", "turn-1", argsA, SafetyProfile.STANDARD))
        dispatcher.endTurn("turn-1")
        // A later turn re-requests the identical action: the card comes back (the denial
        // was per-turn by design — the same-turn set is cleared at turn end).
        broker.scriptNext(
            ApprovalAcquisition.Approved(ApprovalProof("approval-fake", "e".repeat(64))),
        )
        val outcome = dispatcher.dispatch(request("call-2", "turn-2", argsA, SafetyProfile.STANDARD))
        assertTrue(outcome is ToolDispatchOutcome.Succeeded)
        assertEquals(2, broker.acquireCalls.size)
    }

    @Test
    fun theTurnCancelSignalIsForwardedToTheBroker() {
        // The broker's blocking user-decision wait must observe the dispatch's turn-level
        // cancel signal (roadmap HXA-036): a turn stop cancels the wait even when the
        // card-level cancel hook has not registered the card yet — a stopped turn must
        // never keep waiting (and never execute) after the stop.
        val signal =
            object : CancelSignal {
                @Volatile
                var cancelled = false

                override fun isCancelled(): Boolean = cancelled
            }
        broker.scriptNext(ApprovalAcquisition.Denied)
        val request = request("call-1", "turn-1", argsA, SafetyProfile.STANDARD).copy(cancel = signal)
        dispatcher.dispatch(request)
        assertSame("the broker must see the dispatch's cancel signal", signal, broker.acquireCalls.single().cancel)
    }

    @Test
    fun approvedCallExecutesAndConsumesExactlyOnce() {
        broker.scriptNext(
            ApprovalAcquisition.Approved(ApprovalProof("approval-1", "e".repeat(64))),
        )
        val outcome = dispatcher.dispatch(request("call-1", "turn-1", argsA, SafetyProfile.STANDARD))
        val succeeded = outcome as ToolDispatchOutcome.Succeeded
        assertTrue("payload must carry the executor output", succeeded.result.payload.contains("ok"))
        assertEquals(1, broker.consumeCalls.size)
        val event = sink.events.single()
        assertEquals(DispatchOutcomeCode.SUCCESS, event.code)
        assertEquals(RiskLevel.L2, event.riskLevel)
        assertTrue(event.executionStartedAt != null)
    }

    // ------------------------------------------------------------------ fakes

    private class DispatchThread(
        private val body: () -> ToolDispatchOutcome,
    ) : Thread() {
        @Volatile
        var outcome: ToolDispatchOutcome? = null
            private set

        init {
            isDaemon = true
        }

        override fun run() {
            outcome = body()
        }
    }

    /**
     * The broker fake with HOLD: [acquire] records the request and blocks the dispatcher
     * until the test releases the decision (the pending-card window). [scriptNext] scripts
     * an immediate decision instead. Releases are FIFO over the pending queue.
     */
    private class HoldingBroker : ApprovalBroker {
        val acquireCalls = mutableListOf<ApprovalRequest>()
        val consumeCalls = mutableListOf<ApprovalProof>()
        private val script = ArrayDeque<ApprovalAcquisition>()
        private val pendingQueue = ArrayDeque<PendingAcquire>()
        private val queueLock = Any()

        private class PendingAcquire {
            @Volatile
            var result: ApprovalAcquisition? = null
        }

        fun scriptNext(acquisition: ApprovalAcquisition) {
            synchronized(queueLock) {
                script.addLast(acquisition)
            }
        }

        /** Releases the oldest pending acquire (FIFO — the card order). */
        fun releaseNext(acquisition: ApprovalAcquisition) {
            val p: PendingAcquire
            synchronized(queueLock) {
                p = pendingQueue.removeFirstOrNull() ?: error("no pending acquire to release")
            }
            p.result = acquisition
        }

        fun awaitPending(count: Int) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val size =
                    synchronized(queueLock) {
                        acquireCalls.size
                    }
                if (size >= count) return
                Thread.sleep(10)
            }
            error("acquire count reached ${synchronized(queueLock) { acquireCalls.size }}, expected $count")
        }

        override fun acquire(request: ApprovalRequest): ApprovalAcquisition {
            val p = PendingAcquire()
            synchronized(queueLock) {
                acquireCalls += request
                val next = if (script.isNotEmpty()) script.removeFirst() else null
                if (next != null) {
                    p.result = next
                } else {
                    pendingQueue.addLast(p)
                }
            }
            while (p.result == null) {
                Thread.sleep(10)
            }
            return p.result!!
        }

        override fun consume(proof: ApprovalProof) {
            consumeCalls += proof
        }
    }

    private class RecordingSink : AuditSink {
        val events = mutableListOf<DispatchAuditEvent>()

        override fun record(event: DispatchAuditEvent) {
            events += event
        }
    }

    private class FakeClock(
        var instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }
}
