package com.helix.app.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.approval.DispatchAuditRecord
import com.helix.app.approval.StorageAuditSink
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataOrigin
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HXA-192 R2 device acceptance: the `plan.submit` built-in tool (research doc 4.3; HX2-05)
 * wired through the PRODUCTION pipeline — the app's real tool registry, the mode/policy filter
 * and the real [ToolScheduler] + [ToolDispatcher], against REAL Room storage (no Fake
 * PlanRepository, no hand-inserted plan or audit rows: the plan row is written by the registered
 * executor and the audit rows by the storage-backed audit sink).
 *
 * - 归属 / 版本 / READY 行 / 一致性 (R2 task 2): a PLAN-mode `plan.submit` succeeds and its
 *   durable facts agree — the plan row is READY with the exact submitted artifact (v1), the
 *   tool-call row is COMPLETED as `plan.submit` v2 on the same turn, and the audit row settles
 *   SUCCESS correlated to the same call. One call adds exactly ONE plan row (non-idempotent:
 *   never double-inserted by the single call).
 * - 伪造 Turn 参数被拒绝 (R2 task 3): a turn id that never resolved is rejected BEFORE any row
 *   is written (no plan row, no tool-call row).
 * - 取消前的持久结果 (R2 task 3): a call cancelled before it starts ends in the durable
 *   CANCELLED_BEFORE_START audit outcome and writes no plan row (the executor never ran).
 * - 写入后的持久结果 (R2 task 3): the READY plan row + the COMPLETED tool-call row + the
 *   SUCCESS audit row are all persisted and cross-consistent after the call settles.
 *
 * The "external source cannot claim METADATA" boundary (R2 task 3) is proven by the unit
 * contracts (ToolSourceTest / ToolDescriptorTest) — a remote origin can never classify
 * READ_ONLY/METADATA — so it is not re-asserted here.
 */
@RunWith(AndroidJUnit4::class)
class PlanSubmitIntegrationDeviceTest {
    private lateinit var container: AppContainer
    private val clock = SystemClock()

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()
    private val sessionId = "plan-submit-session-$run"
    private val turnId = "plan-submit-turn-$run"

    /** A schema-valid plan.submit argument set (objective + steps + acceptanceCriteria + extras). */
    private val validArgs: JsonObject =
        buildJsonObject {
            put("objective", JsonPrimitive("Migrate the storage layer"))
            put(
                "steps",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("title", JsonPrimitive("Read the spec"))
                            put("description", JsonPrimitive("Read the storage spec"))
                        },
                        buildJsonObject {
                            put("title", JsonPrimitive("Write the migration"))
                            put("description", JsonPrimitive("Write MIGRATION_15_16"))
                        },
                    ),
                ),
            )
            put(
                "acceptanceCriteria",
                JsonArray(
                    listOf(
                        JsonPrimitive("rows survive a schema migration"),
                        JsonPrimitive("no duplicate rows after the migration"),
                    ),
                ),
            )
            put("assumptions", JsonArray(listOf(JsonPrimitive("single writer during migration"))))
            put("risks", JsonArray(listOf(JsonPrimitive("legacy paths may collide on the unique index"))))
        }

    /** A plan missing the REQUIRED acceptanceCriteria: the dispatcher's schema gate rejects it. */
    private val argsMissingCriteria: JsonObject =
        buildJsonObject {
            put("objective", JsonPrimitive("Migrate the storage layer"))
            put(
                "steps",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("title", JsonPrimitive("Read the spec"))
                            put("description", JsonPrimitive("Read the storage spec"))
                        },
                    ),
                ),
            )
        }

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
            container.storage.sessions.create(sessionId, "plan submit device test", null, null, now)
        }
        if (container.storage.turns
                .listBySession(sessionId)
                .none { it.id == turnId }
        ) {
            container.storage.turns.start(turnId, sessionId, now)
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

    /** The durable audit rows for the given call ids, parsed back into their typed records. */
    private fun auditRows(toolCallIds: Set<String>): List<Pair<JsonObject, DispatchAuditRecord>> {
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

    // ------------------------------------------------- R2 task 2: production pipeline + real Room

    @Test
    fun planSubmitThroughTheProductionPipelinePersistsReadyAndConsistentRows() {
        val callId = "plan-ok-$run"
        val before =
            container.storage.plans
                .list()
                .size
        val outcome =
            container.chatService.dispatchToolCall(
                callId,
                turnId,
                "plan.submit",
                validArgs.toString(),
                mode = AgentMode.PLAN,
            )
        val succeeded =
            outcome as? ToolDispatchOutcome.Succeeded
                ?: throw AssertionError("a PLAN-mode plan.submit must succeed, got $outcome")
        val payload = Json.parseToJsonElement(succeeded.result.payload).jsonObject
        val planId = (payload["planId"] as JsonPrimitive).content

        // The persisted plan row is READY (review gate; no evidence yet) with the exact facts.
        val row = container.storage.plans.resolveEntity(planId)
        assertEquals("READY", row.state)
        assertNull(row.evidenceRef)
        assertEquals("Migrate the storage layer", row.objective)
        assertEquals(1, row.version)
        assertEquals((payload["hash"] as JsonPrimitive).content, row.hash)
        assertEquals("READY", (payload["state"] as JsonPrimitive).content)

        // The domain artifact reads back intact: v1, the submitted steps/criteria/assumptions.
        val artifact = container.storage.plans.resolve(planId)
        assertEquals("Migrate the storage layer", artifact.objective)
        assertEquals(2, artifact.steps.size)
        assertEquals(
            listOf("rows survive a schema migration", "no duplicate rows after the migration"),
            artifact.acceptanceCriteria,
        )
        assertEquals(listOf("single writer during migration"), artifact.assumptions)
        assertEquals(1, artifact.version)

        // Ownership + version + consistency: the tool-call row is COMPLETED as plan.submit v2
        // on THIS turn, and exactly ONE plan row was added (one call -> one artifact).
        val callRow =
            container.storage.toolCalls
                .byTurnAndCallId(turnId, callId)
                ?: throw AssertionError("the tool-call row must be persisted for the turn")
        assertEquals("COMPLETED", callRow.state)
        assertEquals("plan.submit", callRow.name)
        assertEquals("2", callRow.version)
        assertEquals(
            before + 1,
            container.storage.plans
                .list()
                .size,
        )

        // The durable audit row settles SUCCESS and correlates to the exact call.
        val rows = auditRows(setOf(callId))
        assertEquals(1, rows.size)
        assertEquals(DispatchOutcomeCode.SUCCESS, rows.single().second.code)
        assertEquals(callId, rows.single().second.correlationId)
    }

    // --------------------------------------------- R2 task 3: forged Turn is rejected up front

    @Test
    fun aForgedTurnIsRejectedBeforeAnyRowIsWritten() {
        val callId = "plan-forged-$run"
        val forgedTurn = "no-such-turn-$run"
        val before =
            container.storage.plans
                .list()
                .size
        // TurnRepository.resolve throws for a turn that never resolved — before any row exists.
        assertThrows(IllegalArgumentException::class.java) {
            container.chatService.dispatchToolCall(
                callId,
                forgedTurn,
                "plan.submit",
                validArgs.toString(),
                mode = AgentMode.PLAN,
            )
        }
        // No plan row and no tool-call row were written for the forged turn.
        assertEquals(
            before,
            container.storage.plans
                .list()
                .size,
        )
        assertNull(container.storage.toolCalls.byTurnAndCallId(forgedTurn, callId))
    }

    // --------------------------------- R2 task 3: schema-invalid plan writes no plan row

    @Test
    fun aSchemaInvalidPlanIsRejectedWithoutWritingAPlanRow() {
        val callId = "plan-bad-$run"
        val before =
            container.storage.plans
                .list()
                .size
        val outcome =
            container.chatService.dispatchToolCall(
                callId,
                turnId,
                "plan.submit",
                argsMissingCriteria.toString(),
                mode = AgentMode.PLAN,
            )
        val denied =
            outcome as? ToolDispatchOutcome.Denied
                ?: throw AssertionError("a plan missing acceptanceCriteria must be rejected, got $outcome")
        assertEquals(DispatchOutcomeCode.INVALID_ARGUMENTS, denied.code)
        // The executor never ran: no plan row, and the tool-call row is NOT COMPLETED.
        assertEquals(
            before,
            container.storage.plans
                .list()
                .size,
        )
        val callRow =
            container.storage.toolCalls
                .byTurnAndCallId(turnId, callId)
                ?: throw AssertionError("the rejected tool-call row must still be persisted")
        assertTrue("a schema-rejected call must not settle COMPLETED", callRow.state != "COMPLETED")
    }

    // ----------------------------------- R2 task 3: cancelled before start writes no plan row

    @Test
    fun aPlanCallCancelledBeforeStartKeepsADurableCancelAndWritesNoPlanRow() {
        val callId = "plan-cancel-$run"
        val before =
            container.storage.plans
                .list()
                .size
        val cancel =
            object : CancelSignal {
                @Volatile
                var cancelled = true

                override fun isCancelled(): Boolean = cancelled
            }
        // plan.submit is already registered in the production registry (both flavors), so the
        // real registry + dispatcher resolve it — only the cancel signal is injected here.
        val request =
            ToolDispatchRequest(
                toolCallId = callId,
                turnId = turnId,
                sessionId = sessionId,
                toolName = ToolName("plan.submit"),
                toolVersion = ToolVersion(2),
                args = validArgs,
                mode = AgentMode.PLAN,
                profile = SafetyProfile.STANDARD,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                dataOrigin = DataOrigin.WORKSPACE,
                scope = null,
                uiToken = "chat:$turnId",
                cancel = cancel,
            )
        val scheduler = ToolScheduler(clock, container.toolPipeline.dispatcher, container.toolPipeline.registry)
        val batch = runBatch(listOf(request), scheduler)
        batch.error?.let { throw AssertionError("cancel settlement failed", it) }
        assertTrue(batch.outcomes.single() is ToolDispatchOutcome.Cancelled)
        // Cancelled before the executor ran: no plan row, but a durable CANCELLED_BEFORE_START.
        assertEquals(
            before,
            container.storage.plans
                .list()
                .size,
        )
        val rows = auditRows(setOf(callId))
        assertEquals(1, rows.size)
        assertEquals(DispatchOutcomeCode.CANCELLED_BEFORE_START, rows.single().second.code)
    }
}
