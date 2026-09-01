package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.StorageAuditSink
import com.helix.app.chat.ChatHistoryBuilder
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ModelRole
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataOrigin
import com.helix.core.storage.repository.InteractionReceiptRepository.ReceiptRequest
import com.helix.core.storage.repository.NotPendingReason
import com.helix.core.storage.repository.ReceiptResult
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.EffectFootprint
import com.helix.tools.framework.EffectFootprintBuilder
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.NoResourceKeys
import com.helix.tools.framework.ResourceKeyExtractor
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.synchronized
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-037 device acceptance (verification-matrix row `:app:connectedConsumerDebugAndroidTest`):
 * the deterministic Tool Scheduler + the fixed back-fill order + the one-time interaction
 * receipts + the durable recovery rows against the PRODUCTION pipeline (real Room storage,
 * the storage-backed audit sink, the real dispatcher, the app's tool registry):
 *
 * - 并发读取/排他屏障: proven read-only calls overlap; a non-read-only call is a full
 *   barrier (first version); the footprint decision is platform code (extractor keys),
 *   never the model's;
 * - 固定回填顺序: completion may be out of order, the batch result and the persisted
 *   model-visible rows are in the ORIGINAL call sequence;
 * - 取消: an unstarted call ends in the durable CANCELLED_BEFORE_START audit outcome
 *   (doc 11 CANCELLED_BEFORE_START) while the started calls complete; a failing item never
 *   cancels the others;
 * - 资源降级: the resource gate lowers (never raises) the effective concurrency;
 * - receipt 一次性: late/duplicate/cancelled/superseded/expired answers return
 *   NOT_PENDING with a stable reason, and a receipt is not an Approval Proof;
 * - 恢复: the durable rows (audit + model-visible tool rows) survive a storage reload
 *   and rebuild the exact model-visible sequence.
 */
@RunWith(AndroidJUnit4::class)
class ToolSchedulerDeviceTest {
    private lateinit var container: AppContainer
    private val clock = SystemClock()

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "sched-session-$run"
    private val turnId = "sched-turn-$run"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
        ensureTurn()
    }

    private fun ensureTurn() {
        val now = System.currentTimeMillis()
        if (container.storage.sessions
                .list()
                .none { it.id == sessionId }
        ) {
            container.storage.sessions.create(sessionId, "scheduler device test", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
        }
    }

    private fun descriptorFor(
        name: String,
        operationClass: ToolOperationClass,
    ): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(1),
            description = "scheduler device test tool",
            inputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").let { it as JsonObject },
            operationClass = operationClass,
            baseRisk = RiskLevel.L0,
            timeout = 30.seconds,
            maxOutputBytes = 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun register(
        name: String,
        operationClass: ToolOperationClass,
        executor: ToolExecutor,
    ) {
        val d = descriptorFor(name, operationClass)
        container.toolPipeline.registry.register(d)
        container.toolPipeline.implementations.register(d, executor)
    }

    private fun request(
        toolCallId: String,
        toolName: String,
        cancel: CancelSignal = NoCancellation,
    ): ToolDispatchRequest =
        ToolDispatchRequest(
            toolCallId = toolCallId,
            turnId = turnId,
            sessionId = sessionId,
            toolName = ToolName(toolName),
            toolVersion = ToolVersion(1),
            args = Json.parseToJsonElement("{}").let { it as JsonObject },
            mode = AgentMode.ACT,
            profile = SafetyProfile.STANDARD,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = null,
            uiToken = "chat:$turnId",
            cancel = cancel,
        )

    private class Span(
        val callId: String,
        val start: Long,
        val end: Long,
    ) {
        fun overlaps(other: Span): Boolean = start < other.end && other.start < end
    }

    private fun timingExecutor(
        holdMillis: Long,
        tag: String,
        spans: MutableList<Span>?,
    ): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                val start = System.nanoTime()
                Thread.sleep(holdMillis)
                if (spans != null) synchronized(spans) { spans.add(Span(call.toolCallId, start, System.nanoTime())) }
                return ToolExecutorResult.Completed(buildJsonObject { put("tag", tag) })
            }
        }

    /** scheduleBatch blocks: run it off the instrumentation (main) thread. */
    private fun runBatch(
        calls: List<ToolDispatchRequest>,
        scheduler: ToolScheduler,
    ): ToolScheduler.BatchResult {
        val latch = CountDownLatch(1)
        val holder = arrayOf<ToolScheduler.BatchResult?>(null)
        val error = arrayOf<Throwable?>(null)
        val t =
            Thread {
                try {
                    holder[0] = scheduler.scheduleBatch(calls)
                } catch (e: Throwable) {
                    error[0] = e
                } finally {
                    latch.countDown()
                }
            }
        t.isDaemon = true
        t.start()
        assertTrue("the batch must finish", latch.await(60, TimeUnit.SECONDS))
        error[0]?.let { throw it }
        return holder[0] ?: error("no batch result")
    }

    private fun auditRows(
        toolCallIds: Set<String>,
    ): List<Pair<JsonObject, com.helix.app.approval.DispatchAuditRecord>> {
        val rows =
            container.storage.auditEvents
                .recent(1000)
                .filter { it.correlationId in toolCallIds }
        return rows.map { row ->
            val record =
                StorageAuditSink.parseRow(
                    row.id,
                    row.correlationId,
                    row.type,
                    row.actor,
                    row.redactedPayload,
                    row.timestamp,
                )
                    ?: error("audit row must parse")
            Json.parseToJsonElement(row.redactedPayload).jsonObject to record
        }
    }

    // ------------------------------------------------------------- fixed back-fill order

    @Test
    fun fixedBackfillOrderSurvivesOutOfOrderCompletion() {
        // call-1 is the SLOW one; it finishes LAST but its result must be FIRST.
        register("sdx.slow.$run", ToolOperationClass.READ_ONLY, timingExecutor(900, "slow", null))
        register("sdx.fast1.$run", ToolOperationClass.READ_ONLY, timingExecutor(40, "fast1", null))
        register("sdx.fast2.$run", ToolOperationClass.READ_ONLY, timingExecutor(40, "fast2", null))
        val scheduler =
            ToolScheduler(clock, container.toolPipeline.dispatcher, container.toolPipeline.registry)
        val ids = listOf("sdx-b1-$run", "sdx-b2-$run", "sdx-b3-$run")
        val batch =
            runBatch(
                listOf(
                    request(ids[0], "sdx.slow.$run"),
                    request(ids[1], "sdx.fast1.$run"),
                    request(ids[2], "sdx.fast2.$run"),
                ),
                scheduler,
            )
        assertNull(batch.error)
        // CALL sequence: slow, fast1, fast2 — regardless of completion speed.
        assertEquals(
            listOf("slow", "fast1", "fast2"),
            batch.outcomes.map {
                val o = it as ToolDispatchOutcome.Succeeded
                (Json.parseToJsonElement(o.result.payload).jsonObject["tag"] as JsonPrimitive).content
            },
        )
        // The durable audit (real Room): three rows, one per call; the slow row finishes
        // LAST (completion was out of order) and every row carries the queue stamp and
        // the attempt id.
        val rows = auditRows(ids.toSet())
        assertEquals(3, rows.size)
        rows.forEach { (payload, record) ->
            assertEquals(
                1,
                (payload["attemptId"] as JsonPrimitive).content.toInt(),
            )
            val queued = payload["queuedAt"]
            assertTrue(
                "queuedAt must be stamped and precede startedAt",
                queued is JsonPrimitive && queued.content.toLong() in 1..record.startedAt,
            )
        }
        val finishedBy = ids.associateWith { id -> rows.single { it.second.correlationId == id }.second.finishedAt }
        assertTrue(
            "the slow call must finish last: completion order was out of order",
            finishedBy[ids[0]]!! >= finishedBy[ids[1]]!! && finishedBy[ids[0]]!! >= finishedBy[ids[2]]!!,
        )
    }

    // ------------------------------------------------- concurrency: overlap + barrier

    @Test
    fun readOnlyOverlapAndExclusiveBarrierOnDevice() {
        val spans = mutableListOf<Span>()
        register("sdx.r1.$run", ToolOperationClass.READ_ONLY, timingExecutor(500, "r1", spans))
        register("sdx.r2.$run", ToolOperationClass.READ_ONLY, timingExecutor(500, "r2", spans))
        register("sdx.w1.$run", ToolOperationClass.LOCAL_MUTATION, timingExecutor(300, "w1", spans))
        val scheduler =
            ToolScheduler(
                clock,
                container.toolPipeline.dispatcher,
                container.toolPipeline.registry,
                maxConcurrency = 2,
            )
        val ids = listOf("sdx-r1-$run", "sdx-r2-$run", "sdx-w1-$run")
        val batch =
            runBatch(
                listOf(
                    request(ids[0], "sdx.r1.$run"),
                    request(ids[1], "sdx.r2.$run"),
                    request(ids[2], "sdx.w1.$run"),
                ),
                scheduler,
            )
        assertNull(batch.error)
        assertEquals(3, batch.outcomes.count { it is ToolDispatchOutcome.Succeeded })
        val r1 = spans.first { it.callId == ids[0] }
        val r2 = spans.first { it.callId == ids[1] }
        val w1 = spans.first { it.callId == ids[2] }
        // Proven read-only calls with disjoint footprints run in parallel:
        assertTrue("the two read-only calls must overlap in flight", r1.overlaps(r2))
        // The non-read-only call is a full barrier: it never overlaps ANY other call.
        assertFalse("a write must not overlap a read (barrier)", w1.overlaps(r1))
        assertFalse("a write must not overlap a read (barrier)", w1.overlaps(r2))
    }

    @Test
    fun theFootprintDecisionIsPlatformCodeNotTheModel() {
        // Same descriptor + args: the platform extractor's keys decide the conflict.
        // The model has no field that can override either answer.
        val d = descriptorFor("sdx.fp-$run", ToolOperationClass.READ_ONLY)
        val sharedKey = ResourceKeyExtractor { _, _ -> setOf("file:a.txt") }
        val otherKey = ResourceKeyExtractor { _, _ -> setOf("file:b.txt") }
        val fpA =
            EffectFootprintBuilder.build(
                d,
                Json.parseToJsonElement("{}").let { it as JsonObject },
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                sharedKey,
            )
        val fpB =
            EffectFootprintBuilder.build(
                d,
                Json.parseToJsonElement("{}").let { it as JsonObject },
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                otherKey,
            )
        assertFalse("a proven read-only call is not exclusive", fpA.exclusive)
        assertTrue("shared resource keys conflict", fpA.conflictsWith(fpA.copy(resourceKeys = setOf("file:a.txt"))))
        assertFalse("disjoint keys do not conflict", fpA.conflictsWith(fpB))
        // An unknown descriptor (the registry cannot resolve it) is conservative:
        // exclusive, never parallel — the safe default, not a model claim.
        val unknown =
            EffectFootprintBuilder.build(
                null,
                Json.parseToJsonElement("{}").let { it as JsonObject },
                ExecutionTargetType.LOCAL_ANDROID,
                null,
                null,
                NoResourceKeys,
            )
        assertTrue(unknown.exclusive)
        // The lane rule: two calls on the same QuickJS lane conflict even when read-only.
        val laneA =
            EffectFootprint(
                ToolOperationClass.READ_ONLY,
                ExecutionTargetType.LOCAL_QUICKJS,
                emptySet(),
                setOf("lane:quickjs"),
                emptySet(),
                exclusive = false,
            )
        val laneB =
            EffectFootprint(
                ToolOperationClass.READ_ONLY,
                ExecutionTargetType.LOCAL_QUICKJS,
                emptySet(),
                setOf("lane:quickjs"),
                emptySet(),
                exclusive = false,
            )
        assertTrue("same Runtime lane serializes", laneA.conflictsWith(laneB))
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun cancelledUnstartedCallKeepsDurableAbortedBeforeStart() {
        register("sdx.cslow.$run", ToolOperationClass.READ_ONLY, timingExecutor(1500, "cs", null))
        register("sdx.cfast.$run", ToolOperationClass.READ_ONLY, timingExecutor(30, "cf", null))
        val cancel =
            object : CancelSignal {
                @Volatile
                var cancelled = false

                override fun isCancelled(): Boolean = cancelled
            }
        // call-3 is cancelled BEFORE it starts (concurrency 2: only two fit in flight).
        val scheduler =
            ToolScheduler(
                clock,
                container.toolPipeline.dispatcher,
                container.toolPipeline.registry,
                maxConcurrency = 2,
            )
        val ids = listOf("sdx-c1-$run", "sdx-c2-$run", "sdx-c3-$run")
        cancel.cancelled = true
        val batch =
            runBatch(
                listOf(
                    request(ids[0], "sdx.cslow.$run"),
                    request(ids[1], "sdx.cfast.$run"),
                    request(ids[2], "sdx.cfast.$run", cancel = cancel),
                ),
                scheduler,
            )
        assertNull(batch.error)
        assertTrue(batch.outcomes[0] is ToolDispatchOutcome.Succeeded)
        assertTrue(batch.outcomes[1] is ToolDispatchOutcome.Succeeded)
        // The queued cancelled call is durably CANCELLED — and its audit row is
        // CANCELLED_BEFORE_START (doc 11: CANCELLED_BEFORE_START), with the queue stamp.
        assertTrue(batch.outcomes[2] is ToolDispatchOutcome.Cancelled)
        val rows = auditRows(ids.toSet())
        assertEquals(3, rows.size)
        val aborted = rows.single { it.second.correlationId == ids[2] }
        assertEquals(DispatchOutcomeCode.CANCELLED_BEFORE_START, aborted.second.code)
        assertTrue(aborted.first["queuedAt"] is JsonPrimitive)
        // The started calls still settled SUCCEEDED in their audit rows.
        assertEquals(DispatchOutcomeCode.SUCCESS, rows.single { it.second.correlationId == ids[0] }.second.code)
        assertEquals(DispatchOutcomeCode.SUCCESS, rows.single { it.second.correlationId == ids[1] }.second.code)
    }

    @Test
    fun oneFailingCallDoesNotCancelTheOthers() {
        register(
            "sdx.fbad.$run",
            ToolOperationClass.READ_ONLY,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                    ToolExecutorResult.Failed("bounded failure")
            },
        )
        register("sdx.fok.$run", ToolOperationClass.READ_ONLY, timingExecutor(120, "ok", null))
        val scheduler =
            ToolScheduler(clock, container.toolPipeline.dispatcher, container.toolPipeline.registry)
        val ids = listOf("sdx-f1-$run", "sdx-f2-$run")
        val batch =
            runBatch(
                listOf(
                    request(ids[0], "sdx.fbad.$run"),
                    request(ids[1], "sdx.fok.$run"),
                ),
                scheduler,
            )
        assertNull("a tool failure settles the item, it does not abort the batch", batch.error)
        assertTrue(batch.outcomes[0] is ToolDispatchOutcome.ExecutionFailed)
        assertTrue(batch.outcomes[1] is ToolDispatchOutcome.Succeeded)
        val rows = auditRows(ids.toSet())
        assertEquals(2, rows.size)
        assertEquals(DispatchOutcomeCode.TOOL_FAILED, rows.single { it.second.correlationId == ids[0] }.second.code)
        assertEquals(DispatchOutcomeCode.SUCCESS, rows.single { it.second.correlationId == ids[1] }.second.code)
    }

    // ------------------------------------------------------------------ resource gate

    @Test
    fun resourceGateOnlyLowersTheEffectiveConcurrency() {
        val spans = mutableListOf<Span>()
        repeat(5) { i ->
            val n = i + 1
            register("sdx.g${n}a.$run", ToolOperationClass.READ_ONLY, timingExecutor(300, "g$n", spans))
        }
        val gate = AtomicInteger(2)
        val scheduler =
            ToolScheduler(
                clock,
                container.toolPipeline.dispatcher,
                container.toolPipeline.registry,
                maxConcurrency = 2,
                resourceGate = { gate.get() },
            )
        val ids = (1..5).map { "sdx-g$it-$run" }
        val calls =
            ids.mapIndexed { index, id ->
                request(id, "sdx.g${index + 1}a.$run")
            }
        val batchFuture =
            java.util.concurrent.CompletableFuture
                .supplyAsync { runBatch(calls, scheduler) }
        Thread.sleep(120)
        val gateChangedAt = System.nanoTime()
        // Low memory / background / thermal pressure: the gate drops to 1 mid-batch.
        gate.set(1)
        val batch = batchFuture.get(60, TimeUnit.SECONDS)
        assertNull(batch.error)
        assertEquals(
            "all five gate calls must settle Succeeded: ${batch.outcomes}",
            5,
            batch.outcomes.count { it is ToolDispatchOutcome.Succeeded },
        )
        // After the drop, every call that STARTED is serialized (gate 1 can only lower):
        // no two post-drop windows overlap.
        val postDrop = synchronized(spans) { spans.toList() }.filter { it.start >= gateChangedAt }.sortedBy { it.start }
        assertTrue("at least three calls must start after the gate drop", postDrop.size >= 3)
        for (i in postDrop.indices) {
            for (j in i + 1 until postDrop.size) {
                assertFalse(
                    "post-drop calls must serialize (gate=1): ${postDrop[i].callId} / ${postDrop[j].callId}",
                    postDrop[i].overlaps(postDrop[j]),
                )
            }
        }
    }

    // --------------------------------------------------------------------- receipts

    @Test
    fun receiptAnswersAreOneTimeWithStableNotPendingReasons() {
        val receipts = container.storage.interactionReceipts
        val now = System.currentTimeMillis()
        // 1) A pending answer succeeds exactly once; the repeat is NOT_PENDING(DUPLICATE).
        receipts.open(
            ReceiptRequest("rc-a-$run", sessionId, turnId, "req-a", 1, "选择哪个文件？", now, 60_000),
        )
        val first = receipts.answer("rc-a-$run", "ans-1", now)
        assertTrue("the first answer must succeed", first is ReceiptResult.Answered)
        val dup = receipts.answer("rc-a-$run", "ans-2", now)
        assertEquals(
            NotPendingReason.DUPLICATE_ANSWER,
            (dup as ReceiptResult.NotPending).reason,
        )
        // 2) A cancelled question: the late answer is NOT_PENDING(CANCELLED).
        receipts.open(
            ReceiptRequest("rc-b-$run", sessionId, turnId, "req-b", 1, "继续吗？", now, 60_000),
        )
        receipts.cancel("rc-b-$run")
        val late = receipts.answer("rc-b-$run", "ans-late", now)
        assertEquals(
            NotPendingReason.CANCELLED,
            (late as ReceiptResult.NotPending).reason,
        )
        // 3) A newer version supersedes the older pending receipt of the same request:
        // answering the older one is NOT_PENDING(SUPERSEDED).
        receipts.open(
            ReceiptRequest("rc-c1-$run", sessionId, turnId, "req-c", 1, "旧问题", now, 60_000),
        )
        receipts.open(
            ReceiptRequest("rc-c2-$run", sessionId, turnId, "req-c", 2, "新问题", now, 60_000),
        )
        val superseded = receipts.answer("rc-c1-$run", "ans-old", now)
        assertEquals(
            NotPendingReason.SUPERSEDED,
            (superseded as ReceiptResult.NotPending).reason,
        )
        // 4) An expired window (createdAt in the past + a 1 ms ttl): NOT_PENDING(EXPIRED).
        receipts.open(
            ReceiptRequest("rc-d-$run", sessionId, turnId, "req-d", 1, "过期问题", now - 5_000, 1),
        )
        val expired = receipts.answer("rc-d-$run", "ans-exp", now)
        assertEquals(
            NotPendingReason.EXPIRED,
            (expired as ReceiptResult.NotPending).reason,
        )
        // 5) An unknown id: NOT_PENDING(UNKNOWN).
        val unknown = receipts.answer("rc-unknown-$run", "ans-x", now)
        assertEquals(
            NotPendingReason.UNKNOWN,
            (unknown as ReceiptResult.NotPending).reason,
        )
        // 6) pending() lists exactly the unexpired, undecided receipts.
        val pending = receipts.pending(sessionId, now)
        assertEquals(listOf("rc-c2-$run"), pending.map { it.id })
        assertEquals("新问题", pending.single().questionSummary)
        // 7) The TTL bound: a window above one hour is refused (ephemeral by design).
        assertThrows(IllegalArgumentException::class.java) {
            receipts.open(
                ReceiptRequest("rc-e-$run", sessionId, turnId, "req-e", 1, "s", now, 61 * 60_000L),
            )
        }
    }

    // --------------------------------------------------------------------- recovery

    @Test
    fun durableToolRowsRebuildTheModelVisibleSequenceAfterReload() {
        // Persist the exact rows the chat service writes for one tool step, then rebuild
        // the model-visible sequence from a FRESH storage read — `model-visible ⇔
        // persisted`, and the process-recovery guarantee (rows are the source of truth;
        // nothing is replayed from memory).
        val msg = container.storage.messages
        msg.append("rcm-u-$run", sessionId, turnId, "USER", ChatHistoryBuilder.KIND_TEXT, "读两个文件")
        msg.append(
            "rcm-a-$run",
            sessionId,
            turnId,
            "ASSISTANT",
            ChatHistoryBuilder.KIND_TOOL_CALLS,
            """[{"id":"rcm-c1-$run","name":"sdx.slow.$run","arguments":"{}"},""" +
                """{"id":"rcm-c2-$run","name":"sdx.fast1.$run","arguments":"{}"}]""",
        )
        msg.append(
            "rcm-r1-$run",
            sessionId,
            turnId,
            "TOOL",
            ChatHistoryBuilder.KIND_TOOL_RESULT,
            """{"id":"rcm-c1-$run","tool":"sdx.slow.$run","status":"SUCCEEDED","summary":"slow"}""",
        )
        msg.append(
            "rcm-r2-$run",
            sessionId,
            turnId,
            "TOOL",
            ChatHistoryBuilder.KIND_TOOL_RESULT,
            """{"id":"rcm-c2-$run","tool":"sdx.fast1.$run","status":"SUCCEEDED","summary":"fast"}""",
        )
        // The tool_call rows are durable too (one per call, settled states).
        val calls = container.storage.toolCalls
        calls.append("rct-1-$run", turnId, "rcm-c1-$run", "sdx.slow.$run", "1", "{}", ToolCallState.COMPLETED.name)
        calls.append("rct-2-$run", turnId, "rcm-c2-$run", "sdx.fast1.$run", "1", "{}", ToolCallState.COMPLETED.name)
        // FRESH read (a new process would see exactly this): rebuild the messages.
        val rows =
            msg
                .listBySession(sessionId)
                .filter { it.turnId == turnId }
                .sortedBy { it.sequence }
                .map { m ->
                    ChatHistoryBuilder.PersistedRow(m.turnId, m.role, m.kind, msg.readContent(m))
                }
        val messages = ChatHistoryBuilder.toModelMessagesStrict(rows)
        assertEquals(
            listOf(ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL, ModelRole.TOOL),
            messages.map { it.role },
        )
        assertEquals(2, messages[1].toolCalls.size)
        assertEquals(listOf("rcm-c1-$run", "rcm-c2-$run"), messages.drop(2).map { it.toolCallId!!.value })
        val callRows = calls.listByTurn(turnId).filter { it.callId in setOf("rcm-c1-$run", "rcm-c2-$run") }
        assertEquals(2, callRows.size)
        assertTrue(callRows.all { it.state == ToolCallState.COMPLETED.name })
    }
}
