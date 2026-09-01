package com.helix.tools.framework

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-037 acceptance (verification-matrix row `:tools:framework:test`): the deterministic
 * Tool Scheduler — bounded platform-decided parallelism from EffectFootprints, the
 * exclusive barrier (first version: non-read-only calls serialize), queue-order fairness,
 * the call-sequence back-fill barrier (results in original order, completion out of
 * order), the resource gate (lower-only), cancellation (an unstarted call ends in the
 * durable CANCELLED_BEFORE_START; one failing item never cancels the others) and the
 * queuedAt/attemptId durable-audit fields.
 */
class ToolSchedulerTest {
    private lateinit var clock: FakeClock
    private lateinit var registry: ToolRegistry
    private lateinit var impls: ToolImplementationRegistry
    private lateinit var broker: ScriptedBroker
    private lateinit var sink: RecordingSink
    private lateinit var dispatcher: ToolDispatcher
    private val usableCaps = mutableSetOf<Capability>()

    @Before
    fun setUp() {
        clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        registry = ToolRegistry()
        impls = ToolImplementationRegistry()
        val center = CapabilityCenter(RecordingResolver(usableCaps, clock))
        broker = ScriptedBroker()
        sink = RecordingSink()
        dispatcher = ToolDispatcher(clock, registry, impls, center, PolicyEngine(clock), broker, sink)
    }

    /** An executor that holds its slot for [holdMillis] and returns the fixed payload. */
    private class TimingExecutor(
        private val holdMillis: Long,
        private val payload: JsonObject,
        private val inFlight: AtomicInteger,
        private val maxSeen: AtomicInteger,
    ) : ToolExecutor {
        override fun execute(call: ExecutableToolCall): ToolExecutorResult {
            val now = inFlight.incrementAndGet()
            maxSeen.accumulateAndGet(now, Math::max)
            Thread.sleep(holdMillis)
            inFlight.decrementAndGet()
            return ToolExecutorResult.Completed(payload)
        }
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun register(
        name: String,
        operationClass: ToolOperationClass,
        baseRisk: RiskLevel,
        executor: ToolExecutor,
    ) {
        val d =
            ToolDescriptor(
                name = ToolName(name),
                version = ToolVersion(1),
                description = "test tool",
                inputSchema = json("""{"type":"object"}"""),
                outputSchema = json("""{"type":"object"}"""),
                operationClass = operationClass,
                baseRisk = baseRisk,
                timeout = 30.seconds,
                maxOutputBytes = 1024L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        registry.register(d)
        impls.register(d, executor)
    }

    private fun call(
        id: String,
        name: String,
        target: ExecutionTargetType = ExecutionTargetType.LOCAL_ANDROID,
        cancel: CancelSignal = NoCancellation,
    ) = ToolDispatchRequest(
        toolCallId = id,
        turnId = "turn-1",
        sessionId = "session-1",
        toolName = ToolName(name),
        toolVersion = ToolVersion(1),
        args = json("{}"),
        mode = AgentMode.ACT,
        profile = SafetyProfile.STANDARD,
        executionTarget = target,
        dataOrigin = DataOrigin.WORKSPACE,
        scope = null,
        uiToken = "ui:t",
        cancel = cancel,
    )

    // ------------------------------------------------------------------ back-fill barrier

    @Test
    fun resultsComeBackInCallOrderEvenWhenCompletionIsOutOfOrder() {
        // call-1 is the SLOW one: it finishes LAST, but its result must be FIRST.
        register(
            "r.slow",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(150, json("""{"i":1}"""), AtomicInteger(), AtomicInteger()),
        )
        register(
            "r.fast1",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(20, json("""{"i":2}"""), AtomicInteger(), AtomicInteger()),
        )
        register(
            "r.fast2",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(20, json("""{"i":3}"""), AtomicInteger(), AtomicInteger()),
        )
        val scheduler = ToolScheduler(clock, dispatcher, registry)
        val calls = listOf(call("call-1", "r.slow"), call("call-2", "r.fast1"), call("call-3", "r.fast2"))
        val batch = scheduler.scheduleBatch(calls)
        assertNull(batch.error)
        assertEquals(
            "results must come back in CALL sequence",
            listOf("""{"i":1}""", """{"i":2}""", """{"i":3}"""),
            batch.outcomes.map { (it as ToolDispatchOutcome.Succeeded).result.payload },
        )
        // The completion order was NOT the call order (proves the barrier re-ordered):
        val completedOrder = sink.events.sortedBy { it.finishedAt }.map { it.correlationId }
        assertTrue("the slow call must finish last: $completedOrder", completedOrder.last() == "call-1")
    }

    // ------------------------------------------------------------------ concurrency rules

    @Test
    fun nonConflictingReadOnlyCallsOverlapAndTheCapBoundsThem() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        register("r.a", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(120, json("{}"), inFlight, maxSeen))
        register("r.b", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(120, json("{}"), inFlight, maxSeen))
        register("r.c", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(120, json("{}"), inFlight, maxSeen))
        val scheduler = ToolScheduler(clock, dispatcher, registry)
        val batch =
            scheduler.scheduleBatch(
                listOf(call("call-a", "r.a"), call("call-b", "r.b"), call("call-c", "r.c")),
            )
        assertNull(batch.error)
        assertEquals(3, batch.outcomes.count { it is ToolDispatchOutcome.Succeeded })
        // Default total concurrency is 2: exactly two overlap, the third waits.
        assertEquals("cap 2 must be honored", 2, maxSeen.get())
    }

    @Test
    fun sharedResourceKeysSerializeReadOnlyCalls() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        register("r.f1", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(60, json("{}"), inFlight, maxSeen))
        register("r.f2", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(60, json("{}"), inFlight, maxSeen))
        val extractor = ResourceKeyExtractor { _, _ -> setOf("file:a.txt") }
        val scheduler = ToolScheduler(clock, dispatcher, registry, resourceKeyExtractor = extractor)
        val batch = scheduler.scheduleBatch(listOf(call("call-1", "r.f1"), call("call-2", "r.f2")))
        assertNull(batch.error)
        assertEquals("same resource key must serialize", 1, maxSeen.get())
    }

    @Test
    fun exclusiveCallsAreAFullBarrierEvenBelowTheCap() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        // One read + two writes: the writes are exclusive (first version: full barrier).
        register("r.x", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(50, json("{}"), inFlight, maxSeen))
        register(
            "w.y",
            ToolOperationClass.LOCAL_MUTATION,
            RiskLevel.L2,
            TimingExecutor(50, json("{}"), inFlight, maxSeen),
        )
        register(
            "w.z",
            ToolOperationClass.LOCAL_MUTATION,
            RiskLevel.L2,
            TimingExecutor(50, json("{}"), inFlight, maxSeen),
        )
        broker.script(
            ApprovalAcquisition.Approved(ApprovalProof("call-w1", "1".repeat(64))),
            ApprovalAcquisition.Approved(ApprovalProof("call-w2", "2".repeat(64))),
        )
        val scheduler = ToolScheduler(clock, dispatcher, registry, maxConcurrency = 2)
        val batch =
            scheduler.scheduleBatch(
                listOf(call("call-r", "r.x"), call("call-w1", "w.y"), call("call-w2", "w.z")),
            )
        assertNull(batch.error)
        assertEquals("exclusive calls never overlap", 1, maxSeen.get())
    }

    @Test
    fun quickJsLaneSerializesAcrossTools() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        register("js.a", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(60, json("{}"), inFlight, maxSeen))
        register("js.b", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(60, json("{}"), inFlight, maxSeen))
        val scheduler = ToolScheduler(clock, dispatcher, registry, maxConcurrency = 2)
        val batch =
            scheduler.scheduleBatch(
                listOf(
                    call("call-1", "js.a", ExecutionTargetType.LOCAL_QUICKJS),
                    call("call-2", "js.b", ExecutionTargetType.LOCAL_QUICKJS),
                ),
            )
        assertNull(batch.error)
        assertEquals("same Runtime lane must serialize", 1, maxSeen.get())
    }

    // ------------------------------------------------------------------ budget / gate / fairness

    @Test
    fun maxConcurrencyAboveTheHardCapIsRejected() {
        register(
            "r.a",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(1, json("{}"), AtomicInteger(), AtomicInteger()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ToolScheduler(clock, dispatcher, registry, maxConcurrency = 5)
        }
        ToolScheduler(clock, dispatcher, registry, maxConcurrency = 4)
    }

    @Test
    fun resourceGateOnlyLowersAndNeverRaises() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        register("g.a", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(60, json("{}"), inFlight, maxSeen))
        register("g.b", ToolOperationClass.READ_ONLY, RiskLevel.L0, TimingExecutor(60, json("{}"), inFlight, maxSeen))
        // Gate 1 with cap 2: effective 1 (the gate lowers).
        var gate = 1
        val lowering = ToolScheduler(clock, dispatcher, registry, maxConcurrency = 2, resourceGate = { gate })
        val batch = lowering.scheduleBatch(listOf(call("call-1", "g.a"), call("call-2", "g.b")))
        assertNull(batch.error)
        assertEquals("gate 1 must serialize", 1, maxSeen.get())
        // Gate 4 with cap 1: effective 1 (the gate can never raise).
        maxSeen.set(0)
        gate = 4
        val capped = ToolScheduler(clock, dispatcher, registry, maxConcurrency = 1, resourceGate = { gate })
        capped.scheduleBatch(listOf(call("call-1", "g.a"), call("call-2", "g.b")))
        assertEquals("gate must never raise the cap", 1, maxSeen.get())
    }

    @Test
    fun admissionFollowsQueueOrder() {
        // Slots = 1: while call-1 holds the slot, the queue must admit call-2 before
        // call-3 (FIFO admission, no re-queueing).
        val starts = mutableListOf<String>()
        val recording =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    synchronized(starts) { starts += call.toolCallId }
                    Thread.sleep(40)
                    return ToolExecutorResult.Completed(json("{}"))
                }
            }
        register("q.a", ToolOperationClass.READ_ONLY, RiskLevel.L0, recording)
        register("q.b", ToolOperationClass.READ_ONLY, RiskLevel.L0, recording)
        register("q.c", ToolOperationClass.READ_ONLY, RiskLevel.L0, recording)
        val scheduler = ToolScheduler(clock, dispatcher, registry, maxConcurrency = 1)
        val batch =
            scheduler.scheduleBatch(
                listOf(call("call-1", "q.a"), call("call-2", "q.b"), call("call-3", "q.c")),
            )
        assertNull(batch.error)
        assertEquals(listOf("call-1", "call-2", "call-3"), starts)
    }

    // ------------------------------------------------------------------ cancellation / independence

    @Test
    fun cancelledUnstartedCallEndsInDurableAbortedBeforeStart() {
        val gate = CountDownLatch(1)
        register(
            "c.slow",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    gate.countDown()
                    Thread.sleep(120)
                    return ToolExecutorResult.Completed(json("{}"))
                }
            },
        )
        register(
            "c.fast",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(1, json("{}"), AtomicInteger(), AtomicInteger()),
        )
        val cancel = ManualCancel()
        // call-2 is queued behind call-1 (concurrency 1) and cancelled before it starts.
        val scheduler = ToolScheduler(clock, dispatcher, registry, maxConcurrency = 1)
        val calls =
            listOf(
                call("call-1", "c.slow"),
                call("call-2", "c.fast", cancel = cancel),
            )
        val batchFuture =
            CompletableFuture.supplyAsync {
                scheduler.scheduleBatch(calls)
            }
        // Wait until the SLOW call has actually started, then cancel the turn while the
        // queued call has not started yet.
        assertTrue("the slow call must start", gate.await(5, TimeUnit.SECONDS))
        cancel.cancelled = true
        val batch = batchFuture.join()
        assertNull(batch.error)
        assertTrue(batch.outcomes[0] is ToolDispatchOutcome.Succeeded)
        assertTrue(
            "the queued cancelled call must be durably CANCELLED",
            batch.outcomes[1] is ToolDispatchOutcome.Cancelled,
        )
        // The durable audit (doc 11: ABORTED_BEFORE_START): the unstarted call's row is
        // CANCELLED_BEFORE_START with its queuedAt stamp and the attempt metadata.
        val aborted = sink.events.first { it.correlationId == "call-2" }
        assertEquals(DispatchOutcomeCode.CANCELLED_BEFORE_START, aborted.code)
        assertEquals(1, aborted.attemptId)
        assertTrue(
            "queuedAt must be stamped and precede startedAt",
            aborted.queuedAt != null && aborted.startedAt >= aborted.queuedAt,
        )
        // The started call's audit carries the same queue/attempt metadata.
        val ran = sink.events.first { it.correlationId == "call-1" }
        assertEquals(DispatchOutcomeCode.SUCCESS, ran.code)
        assertEquals(1, ran.attemptId)
        assertEquals("startedAt must follow queuedAt", true, ran.queuedAt != null && ran.startedAt >= ran.queuedAt)
    }

    @Test
    fun oneFailingItemNeverCancelsTheOthers() {
        register(
            "f.bad",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = ToolExecutorResult.Failed("boom")
            },
        )
        register(
            "f.ok",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(60, json("{}"), AtomicInteger(), AtomicInteger()),
        )
        val scheduler = ToolScheduler(clock, dispatcher, registry)
        val batch =
            scheduler.scheduleBatch(
                listOf(call("call-1", "f.bad"), call("call-2", "f.ok")),
            )
        assertNull("a tool failure is a settled outcome, not a batch error", batch.error)
        assertTrue(batch.outcomes[0] is ToolDispatchOutcome.ExecutionFailed)
        assertTrue(batch.outcomes[1] is ToolDispatchOutcome.Succeeded)
    }

    @Test
    fun dispatcherControlFlowSurfacesAsBatchErrorWithSettledOutcomes() {
        // A turn stop during the approval wait: the dispatcher throws the control flow;
        // the scheduler must surface it as [ToolScheduler.BatchResult.error] while still
        // returning the settled outcomes of the independent calls.
        register(
            "t.a",
            ToolOperationClass.READ_ONLY,
            RiskLevel.L0,
            TimingExecutor(1, json("{}"), AtomicInteger(), AtomicInteger()),
        )
        register(
            "t.b",
            ToolOperationClass.LOCAL_MUTATION,
            RiskLevel.L2,
            TimingExecutor(1, json("{}"), AtomicInteger(), AtomicInteger()),
        )

        class ThrowingBroker : ApprovalBroker {
            override fun acquire(request: ApprovalRequest): ApprovalAcquisition = throw TurnStopForTest("stop")

            override fun consume(proof: ApprovalProof) {
                Unit
            }

            override fun reMint(proof: ApprovalProof): ApprovalProof? = null
        }
        val throwingDispatcher =
            ToolDispatcher(
                clock,
                registry,
                impls,
                CapabilityCenter(RecordingResolver(usableCaps, clock)),
                PolicyEngine(clock),
                ThrowingBroker(),
                sink,
            )
        val scheduler = ToolScheduler(clock, throwingDispatcher, registry)
        val batch =
            scheduler.scheduleBatch(
                listOf(call("call-a", "t.a"), call("call-b", "t.b")),
            )
        assertTrue(batch.error is TurnStopForTest)
        // The independent read still settled SUCCEEDED; the aborted slot is null.
        assertTrue(batch.outcomes[0] is ToolDispatchOutcome.Succeeded)
        assertEquals("the aborted slot is null", null, batch.outcomes[1])
    }

    private class TurnStopForTest(
        message: String,
    ) : RuntimeException(message)

    // ------------------------------------------------------------------ fixtures (mirrors ToolDispatcherTest)

    private class FakeClock(
        var instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    private class RecordingResolver(
        private val usable: MutableSet<Capability>,
        private val clock: Clock,
    ) : CapabilityResolver {
        override fun resolve(capability: Capability): CapabilityGrant =
            CapabilityGrant(
                capability = capability,
                state = if (capability in usable) GrantState.GRANTED else GrantState.UNAVAILABLE,
                grantedBySystem = true,
                userScope = null,
                checkedAt = clock.now(),
            )
    }

    private class ScriptedBroker : ApprovalBroker {
        val scripted = ArrayDeque<ApprovalAcquisition>()
        val consumeCalls = mutableListOf<ApprovalProof>()

        fun script(vararg acquisitions: ApprovalAcquisition) {
            scripted.addAll(acquisitions)
        }

        override fun acquire(request: ApprovalRequest): ApprovalAcquisition {
            check(scripted.isNotEmpty()) { "scheduler test broker scripted empty" }
            return scripted.removeFirst()
        }

        override fun consume(proof: ApprovalProof) {
            consumeCalls += proof
        }

        override fun reMint(proof: ApprovalProof): ApprovalProof? = proof
    }

    private class RecordingSink : AuditSink {
        val events = mutableListOf<DispatchAuditEvent>()

        override fun record(event: DispatchAuditEvent) {
            events += event
        }
    }

    private class ManualCancel : CancelSignal {
        @Volatile
        var cancelled = false

        override fun isCancelled(): Boolean = cancelled
    }
}
