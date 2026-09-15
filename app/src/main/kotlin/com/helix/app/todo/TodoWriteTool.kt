package com.helix.app.todo

import com.helix.core.agent.TaskItem
import com.helix.core.agent.TaskItemState
import com.helix.core.agent.TaskLedger
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * The `todo.write` built-in tool (research doc sections 13/16; HX2-07): the model's
 * working-memory ledger. Each call REPLACES the whole ledger with the full current list of
 * items and their states (todo / in_progress / done / blocked). The durable record is the
 * tool-call row the dispatcher persists around this executor — exactly like `goal.report`,
 * this tool only validates and echoes; [TaskLedgerProjection] reads the latest successful
 * call back into the conversation's Progress section.
 *
 * Classification: METADATA at L0 — the internal metadata-operation contract (research doc
 * section 4), NOT a disguised READ_ONLY: its one durable side effect is internal harness
 * state (the persisted call row), no user-visible local mutation, no egress, no file path or
 * foreign Goal ID in its input. IDEMPOTENT: the call declares state, the latest call wins,
 * and a replay with the same args leaves the same ledger. It is valid in every mode (ACT or
 * GOAL, bound or unbound, and a plain chat task) — unlike `goal.report` there is no goal
 * gate: a chat task keeps its progress too.
 */
internal object TodoWriteTool {
    const val NAME: String = "todo.write"

    const val VERSION: Int = 1

    /** Model-facing contract; the executor re-checks every invariant (defense in depth). */
    private val schema =
        Json
            .parseToJsonElement(
                """{
            "type":"object",
            "properties":{
                "items":{
                    "type":"array",
                    "minItems":1,
                    "maxItems":50,
                    "items":{
                        "type":"object",
                        "properties":{
                            "id":{"type":"string","minLength":1,"maxLength":64},
                            "title":{"type":"string","minLength":1,"maxLength":200},
                            "state":{"type":"string","enum":["todo","in_progress","done","blocked"]}
                        },
                        "required":["id","title","state"],
                        "additionalProperties":false
                    }
                }
            },
            "required":["items"],
            "additionalProperties":false
        }""",
            ).jsonObject

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
    ) {
        val descriptor =
            ToolDescriptor(
                name = ToolName(NAME),
                version = ToolVersion(VERSION),
                description =
                    """
                    Maintain your task progress ledger for the current work. Call it at the start of a
                    multi-step task with the planned items, then call it again whenever any item changes
                    state, passing the FULL list every time (each call replaces the earlier ledger).
                    States: todo (not started), in_progress (currently working on it — at most one item
                    may be in_progress), done (finished), blocked (waiting on something external you
                    cannot fix yourself). Keep titles short imperative phrases. This only records your
                    progress; it grants no permissions and performs no work.
                    """.trimIndent().replace("\n", " "),
                inputSchema = schema,
                outputSchema = schema,
                operationClass = ToolOperationClass.METADATA,
                baseRisk = RiskLevel.L0,
                timeout = 5.seconds,
                maxOutputBytes = 32768,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        registry.register(descriptor)
        implementations.register(
            descriptor,
            object : ToolExecutor {
                @Suppress("ReturnCount") // cancellation boundary + the fail-closed validation exit
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                    return try {
                        ledgerFromArgs(call.args)
                        // The echo IS the model-visible result: the normalized ledger it just wrote.
                        ToolExecutorResult.Completed(call.args)
                    } catch (e: IllegalArgumentException) {
                        // Nothing was persisted by this executor; the dispatcher's row only
                        // records the failed call. The stable label is model-visible.
                        ToolExecutorResult.Failed(e.message ?: "the task list is invalid", sideEffectFree = true)
                    }
                }
            },
        )
    }

    /**
     * Schema-validated args -> the validated [TaskLedger] value. Throws [IllegalArgumentException]
     * on any invariant breach (duplicate id, over-limit, unknown state, two in_progress) —
     * the dispatcher's schema check runs first, this is the second, independent check.
     */
    private fun ledgerFromArgs(args: JsonObject): TaskLedger {
        val items =
            (args["items"] as? JsonArray)?.map { itemFromElement(it) }
                ?: throw IllegalArgumentException("items is required")
        val ledger = TaskLedger(items)
        require(items.count { it.state == TaskItemState.IN_PROGRESS } <= 1) {
            "at most one item may be in_progress"
        }
        return ledger
    }

    private fun itemFromElement(element: JsonElement): TaskItem {
        val item = element as? JsonObject ?: throw IllegalArgumentException("items entries must be objects")
        return TaskItem(
            id = requiredString(item, "id"),
            title = requiredString(item, "title"),
            state = stateFromName(requiredString(item, "state")),
        )
    }

    private fun requiredString(
        item: JsonObject,
        field: String,
    ): String =
        (item[field] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("$field is required")

    private fun stateFromName(name: String): TaskItemState =
        when (name) {
            "todo" -> TaskItemState.TODO
            "in_progress" -> TaskItemState.IN_PROGRESS
            "done" -> TaskItemState.DONE
            "blocked" -> TaskItemState.BLOCKED
            else -> throw IllegalArgumentException("unknown state $name")
        }
}
