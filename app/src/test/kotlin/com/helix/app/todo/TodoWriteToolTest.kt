package com.helix.app.todo

import com.helix.core.agent.ModeDecision
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for the `todo.write` built-in tool (research doc section 16; HX2-07): the
 * contract facts, the executor's echo-and-validate behavior and its fail-closed exits,
 * plus the [TaskLedgerProjection.itemsFromArgs] parse rules — no dispatcher, no Room.
 */
class TodoWriteToolTest {
    private val registry = ToolRegistry()
    private val implementations = ToolImplementationRegistry()

    init {
        TodoWriteTool.register(registry, implementations)
    }

    private fun item(
        id: String,
        title: String,
        state: String,
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            put("state", JsonPrimitive(state))
        }

    private fun args(vararg items: JsonObject): JsonObject = buildJsonObject { put("items", JsonArray(items.toList())) }

    private fun call(
        args: JsonObject,
        cancel: CancelSignal = NoCancellation,
    ) = ExecutableToolCall(
        toolCallId = "tc-1",
        toolName = TodoWriteTool.NAME,
        toolVersion = "1",
        args = args,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        deadline = Instant.now().plusSeconds(30),
        cancel = cancel,
        sessionId = "s1",
        turnId = "t1",
    )

    private fun executor() = implementations.resolve(ToolName(TodoWriteTool.NAME), ToolVersion(TodoWriteTool.VERSION))

    private fun failed(result: ToolExecutorResult): ToolExecutorResult.Failed =
        result as? ToolExecutorResult.Failed ?: throw AssertionError("expected Failed, got $result")

    // --- the contract ---

    @Test
    fun theContractIsAReadOnlyL0BuiltInIdempotentToolAdmittedByPlanMode() {
        val d = registry.resolve(ToolName(TodoWriteTool.NAME), ToolVersion(TodoWriteTool.VERSION))
        assertEquals(ToolOperationClass.READ_ONLY, d.operationClass)
        assertEquals(RiskLevel.L0, d.baseRisk)
        assertEquals(Idempotency.IDEMPOTENT, d.idempotency)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        // The ledger is available in PLAN mode too: READ_ONLY at dynamic risk <= L1
        // (core:agent ModePolicy) — a planning conversation keeps its steps legible.
        assertTrue(
            ModePolicy.evaluate(
                AgentMode.PLAN,
                ToolModeProfile(ToolOperationClass.READ_ONLY, RiskLevel.L0),
            ) is ModeDecision.Allowed,
        )
    }

    // --- the executor: echo-and-validate ---

    @Test
    fun aValidLedgerIsAcceptedAndEchoedBackVerbatim() {
        val input =
            args(
                item("a", "Read the spec", "todo"),
                item("b", "Write the migration", "in_progress"),
                item("c", "Run the gates", "done"),
            )
        val result = executor().execute(call(input))

        val completed =
            result as? ToolExecutorResult.Completed
                ?: throw AssertionError("expected Completed, got $result")
        // The echo IS the model-visible result: exactly the ledger the model just wrote.
        assertEquals(input, completed.output)
    }

    @Test
    fun aLedgerWithTwoInProgressItemsFailsSideEffectFree() {
        val result =
            executor().execute(
                call(
                    args(
                        item("a", "first", "in_progress"),
                        item("b", "second", "in_progress"),
                    ),
                ),
            )
        val f = failed(result)
        assertEquals("at most one item may be in_progress", f.detail)
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun aLedgerWithADuplicateIdFailsSideEffectFree() {
        val result =
            executor().execute(call(args(item("a", "first", "todo"), item("a", "second", "todo"))))
        val f = failed(result)
        assertEquals("task ids must be unique within a ledger", f.detail)
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun anOverLimitLedgerFailsSideEffectFree() {
        val result =
            executor().execute(
                call(args(*(1..51).map { item("id-$it", "step $it", "todo") }.toTypedArray())),
            )
        val f = failed(result)
        assertEquals("a ledger holds <= 50 items", f.detail)
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun anOverLongTitleFailsSideEffectFree() {
        val result = executor().execute(call(args(item("a", "x".repeat(201), "todo"))))
        val f = failed(result)
        assertEquals("task title must be 1..200 non-blank characters", f.detail)
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun malformedArgsFailSideEffectFreeWithTheStableLabel() {
        val unknownState = executor().execute(call(args(item("a", "t", "paused"))))
        assertEquals("unknown state paused", failed(unknownState).detail)

        val missingState =
            executor().execute(
                call(
                    buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("id", JsonPrimitive("a"))
                                        put("title", JsonPrimitive("t"))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            )
        assertEquals("state is required", failed(missingState).detail)

        val nonObjectEntry =
            executor().execute(call(buildJsonObject { put("items", JsonArray(listOf(JsonPrimitive("x")))) }))
        assertEquals("items entries must be objects", failed(nonObjectEntry).detail)

        val noItems = executor().execute(call(buildJsonObject {}))
        assertEquals("items is required", failed(noItems).detail)
    }

    @Test
    fun aCancelledCallIsCancelled() {
        val result =
            executor().execute(
                call(
                    args(item("a", "t", "todo")),
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled(): Boolean = true
                        },
                ),
            )
        assertTrue(result is ToolExecutorResult.Cancelled)
    }

    // --- the projection parse rules (fail-closed) ---

    @Test
    fun itemsFromArgsMapsKnownStatesAndSkipsMalformedEntries() {
        val args =
            buildJsonObject {
                put(
                    "items",
                    JsonArray(
                        listOf(
                            item("a", "Read the spec", "in_progress"),
                            item("b", "Write the migration", "done"),
                            buildJsonObject {
                                // blank title -> skipped
                                put("id", JsonPrimitive("c"))
                                put("title", JsonPrimitive(" "))
                                put("state", JsonPrimitive("todo"))
                            },
                            buildJsonObject {
                                // unknown state -> skipped
                                put("id", JsonPrimitive("d"))
                                put("title", JsonPrimitive("blocked?"))
                                put("state", JsonPrimitive("paused"))
                            },
                            JsonPrimitive("not-an-object"),
                        ),
                    ),
                )
            }

        val items = TaskLedgerProjection.itemsFromArgs(args)

        assertEquals(
            listOf(
                LedgerItemUi("a", "Read the spec", "in_progress"),
                LedgerItemUi("b", "Write the migration", "done"),
            ),
            items,
        )
    }

    @Test
    fun itemsFromArgsYieldsAnEmptyListOnMalformedShape() {
        // a corrupt row never renders a partial or fake ledger
        assertEquals(
            emptyList<LedgerItemUi>(),
            TaskLedgerProjection.itemsFromArgs(buildJsonObject { put("items", JsonPrimitive("nope")) }),
        )
        assertEquals(emptyList<LedgerItemUi>(), TaskLedgerProjection.itemsFromArgs(buildJsonObject {}))
    }
}
