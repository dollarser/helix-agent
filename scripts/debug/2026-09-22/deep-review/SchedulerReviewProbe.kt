package com.helix.tools.framework

import com.helix.core.model.*
import com.helix.core.policy.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class SchedulerReviewProbe {
    private val clock = object : Clock { override fun now(): Instant = Instant.now() }
    private val registry = ToolRegistry()
    private val implementations = ToolImplementationRegistry()
    private val dispatcher = ToolDispatcher(
        clock, registry, implementations,
        CapabilityCenter(object : CapabilityResolver {
            override fun resolve(capability: Capability) = CapabilityGrant(
                capability, GrantState.GRANTED, true, null, clock.now())
        }), PolicyEngine(clock),
        object : ApprovalBroker {
            override fun acquire(request: ApprovalRequest) =
                ApprovalAcquisition.Approved(ApprovalProof("probe-approval", "a".repeat(64)))
            override fun consume(proof: ApprovalProof) = Unit
            override fun reMint(proof: ApprovalProof): ApprovalProof = proof
        }, object : AuditSink { override fun record(event: DispatchAuditEvent) = Unit })

    private fun register(name: String, mutation: Boolean, body: () -> Unit) {
        val schema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        val descriptor = ToolDescriptor(ToolName(name), ToolVersion(1), "review fixture", schema, schema,
            if (mutation) ToolOperationClass.LOCAL_MUTATION else ToolOperationClass.READ_ONLY,
            if (mutation) RiskLevel.L2 else RiskLevel.L0, 10.seconds, 1024L, emptySet(),
            Idempotency.IDEMPOTENT, ExecutionTargetType.LOCAL_ANDROID, ToolOrigin.BuiltInOrigin)
        registry.register(descriptor)
        implementations.register(descriptor, object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                body()
                return ToolExecutorResult.Completed(JsonObject(emptyMap()))
            }
        })
    }
    private fun call(id: String, name: String) = ToolDispatchRequest(
        toolCallId=id, turnId="probe-turn", sessionId="probe-session", toolName=ToolName(name),
        toolVersion=ToolVersion(1), args=JsonObject(emptyMap()), mode=AgentMode.ACT,
        profile=SafetyProfile.STANDARD, executionTarget=ExecutionTargetType.LOCAL_ANDROID,
        dataOrigin=DataOrigin.WORKSPACE, scope=null, uiToken="probe")

    @Test fun laterReadPassesAQueuedExclusiveMutation() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val lastStarted = CountDownLatch(1)
        val starts = CopyOnWriteArrayList<String>()
        register("probe.first", false) { starts += "first"; firstStarted.countDown(); check(releaseFirst.await(5, TimeUnit.SECONDS)) }
        register("probe.write", true) { starts += "write" }
        register("probe.last", false) { starts += "last"; lastStarted.countDown() }
        val scheduler = ToolScheduler(clock, dispatcher, registry, maxConcurrency=2)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val batch = worker.submit<ToolScheduler.BatchResult> { scheduler.scheduleBatch(listOf(
                call("first", "probe.first"), call("write", "probe.write"), call("last", "probe.last"))) }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            assertTrue("BUG reproduced: later read starts before prior write", lastStarted.await(3, TimeUnit.SECONDS))
            assertFalse(starts.contains("write"))
            releaseFirst.countDown()
            val result = batch.get(5, TimeUnit.SECONDS)
            assertEquals(3, result.settlements.size)
            assertNull(result.firstError)
            assertTrue(result.outcomes.all { it is ToolDispatchOutcome.Succeeded })
            assertTrue(starts.indexOf("last") < starts.indexOf("write"))
            println("REPRODUCED scheduler barrier: $starts")
        } finally { releaseFirst.countDown(); worker.shutdownNow() }
    }

    @Test fun releaseBetweenFailedAdmissionAndSignalReadLosesWake() {
        val scheduler = ToolScheduler(clock, dispatcher, registry, maxConcurrency=1)
        val footprint = EffectFootprint(ToolOperationClass.READ_ONLY, ExecutionTargetType.LOCAL_ANDROID,
            emptySet(), emptySet(), emptySet(), false)
        val claim = scheduler.javaClass.getDeclaredMethod("tryClaimSlot", String::class.java, EffectFootprint::class.java).apply { isAccessible=true }
        val release = scheduler.javaClass.getDeclaredMethod("releaseSlot", String::class.java).apply { isAccessible=true }
        val signalField = scheduler.javaClass.getDeclaredField("slotStateSignal").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val signal = signalField.get(scheduler) as AtomicReference<CompletableFuture<Void>>
        assertEquals(true, claim.invoke(scheduler, "batch-a", footprint))
        assertEquals(false, claim.invoke(scheduler, "batch-b", footprint))
        val oldSignal = signal.get()
        // Exact gap in scheduleReservedBatch: no available slot was observed, but
        // the global signal is not captured until AFTER admission returns.
        release.invoke(scheduler, "batch-a")
        val waiter = CompletableFuture<Void>()
        signal.get().whenComplete { _, _ -> waiter.complete(null) }
        assertTrue(oldSignal.isDone)
        assertFalse("BUG reproduced: waiter subscribes to replacement signal", waiter.isDone)
        assertEquals(true, claim.invoke(scheduler, "proof-slot-is-free", footprint))
        println("REPRODUCED scheduler wake interleaving: oldSignalDone=true, newWaiterDone=false, slotFree=true")
        release.invoke(scheduler, "proof-slot-is-free")
    }
}
