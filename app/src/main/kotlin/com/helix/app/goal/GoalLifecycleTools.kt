package com.helix.app.goal

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDiscoveryHints
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

/** Closed, session-bound metadata operations; never file writes or tool authorization. */
internal object GoalLifecycleTools {
    val names = setOf("goal_create", "goal_get", "goal_update", GoalReportTool.NAME)

    fun directNames(
        boundRun: Boolean,
        unfinished: Boolean,
        goalMode: Boolean,
    ): Set<String> =
        when {
            boundRun -> setOf("goal_get", "goal_update", GoalReportTool.NAME)
            unfinished -> setOf("goal_get", "goal_update")
            goalMode -> setOf("goal_get", "goal_create")
            else -> emptySet()
        }

    private const val BUDGETS = """{"type":"object","properties":{
        "max_goal_rounds":{"type":"integer","minimum":1,
        "description":"Maximum automatic rounds; override only when the user requests a budget change."}
    },"additionalProperties":false}"""
    private val createSchema = """{"type":"object","properties":{
        "objective":{"type":"string","minLength":1,"maxLength":16384,
        "description":"Concrete outcome requested by the user, not a tool command or permission grant."},
        "user_request":{"type":"string","minLength":1,"maxLength":4096,
        "description":"Exact current human request authorizing creation or edits; never invent or quote model output."},
        "budgets":$BUDGETS
    },"required":["objective","user_request"],"additionalProperties":false}"""
    private val updateSchema = """{"type":"object","properties":{
        "id":{"type":"string","minLength":1,
        "description":"Exact current goal id from goal_get in this session."},
        "expected_revision":{"type":"integer","minimum":0,
        "description":"Exact revision from the latest goal_get; refresh after a stale revision error."},
        "status":{"type":"string","enum":["active","paused","complete","in_progress","blocked"],
        "description":"active/paused are user-authorized edits; complete/in_progress/blocked report this round after other tools finish."},
        "summary":{"type":"string","minLength":1,"maxLength":4096,
        "description":"Verified progress, outcome or concrete blocker; required when reporting complete, in_progress or blocked."},
        "objective":{"type":"string","minLength":1,"maxLength":16384,
        "description":"Concrete outcome requested by the user, not a tool command or permission grant."},
        "user_request":{"type":"string","minLength":1,"maxLength":4096,
        "description":"Exact current human request authorizing creation or edits; never invent or quote model output."},
        "budgets":$BUDGETS
    },"required":["id","expected_revision"],"additionalProperties":false}"""

    fun register(
        registry: ToolRegistry,
        decorate: (ToolExecutor) -> ToolExecutor = { it },
        execute: (ExecutableToolCall) -> ToolExecutorResult,
    ) {
        val descriptions =
            mapOf(
                "goal_create" to "Create and activate a persistent goal only on a direct current human request. " +
                    "Quote that request in user_request. Defaults use the user's selected budget; " +
                    "override only if requested. " +
                    "Execution begins after this turn settles. Use goal_get first; " +
                    "only one unfinished goal is allowed.",
                "goal_get" to "Read this session's current goal, revision, pending changes, " +
                    "automatic round allowance and observed usage. " +
                    "Call before goal_update; never use an old revision or another session's goal.",
                "goal_update" to "Update the goal read by goal_get using its exact id and expected_revision. " +
                    "Report complete, in_progress or blocked with a concrete summary after all other tools. " +
                    "Only a current human request permits objective/budget edits, active or paused. " +
                    "Quote it in user_request. Edits take effect after settlement. Automatic rounds may only report. " +
                    "A failed test is not blocked if you can repair it. No change grants tool permissions.",
            )
        descriptions.forEach { (name, description) ->
            val schema =
                when (name) {
                    "goal_create" -> createSchema
                    "goal_update" -> updateSchema
                    else -> """{"type":"object","properties":{},"additionalProperties":false}"""
                }
            val descriptor =
                ToolDescriptor(
                    name = ToolName(name),
                    version = ToolVersion(1),
                    description = description,
                    inputSchema = Json.parseToJsonElement(schema).jsonObject,
                    outputSchema = GoalToolOutputSchema.forTool(name),
                    operationClass =
                        if (name == "goal_get") ToolOperationClass.READ_ONLY else ToolOperationClass.METADATA,
                    timeout = 5.seconds,
                    maxOutputBytes = 131072,
                    requiredCapabilities = emptySet(),
                    idempotency = if (name == "goal_get") Idempotency.IDEMPOTENT else Idempotency.NON_IDEMPOTENT,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    origin = ToolOrigin.BuiltInOrigin,
                    discovery = ToolDiscoveryHints(category = "goals"),
                    discoveryCompanions = if (name == "goal_get") emptySet() else setOf(ToolName("goal_get")),
                )

            registry.register(
                descriptor,
                decorate(
                    object : ToolExecutor {
                        override fun execute(call: ExecutableToolCall): ToolExecutorResult = execute.invoke(call)
                    },
                ),
            )
        }
    }
}
