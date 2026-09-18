package com.helix.app.goal

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
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

/** Closed, session-bound metadata operations; never file writes or tool authorization. */
internal object GoalLifecycleTools {
    val names = setOf("create_goal", "get_goal", "update_goal", GoalReportTool.NAME)
    private const val BUDGETS = """{"type":"object","properties":{
        "max_model_calls":{"type":"integer","minimum":1},
        "max_tool_calls":{"type":"integer","minimum":1},
        "max_tokens":{"type":"integer","minimum":1},
        "max_runtime_millis":{"type":"integer","minimum":1},
        "max_round_millis":{"type":"integer","minimum":1},
        "max_retries":{"type":"integer","minimum":0}
    },"additionalProperties":false}"""
    private val createSchema = """{"type":"object","properties":{
        "objective":{"type":"string","minLength":1,"maxLength":16384},
        "user_request":{"type":"string","minLength":1,"maxLength":4096},
        "budgets":$BUDGETS
    },"required":["objective","user_request"],"additionalProperties":false}"""
    private val updateSchema = """{"type":"object","properties":{
        "id":{"type":"string","minLength":1},
        "expected_revision":{"type":"integer","minimum":0},
        "status":{"type":"string","enum":["active","paused","complete","in_progress","blocked"]},
        "summary":{"type":"string","minLength":1,"maxLength":4096},
        "objective":{"type":"string","minLength":1,"maxLength":16384},
        "user_request":{"type":"string","minLength":1,"maxLength":4096},
        "budgets":$BUDGETS
    },"required":["id","expected_revision"],"additionalProperties":false}"""

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        decorate: (ToolExecutor) -> ToolExecutor = { it },
        execute: (ExecutableToolCall) -> ToolExecutorResult,
    ) {
        val descriptions =
            mapOf(
                "create_goal" to "Create and activate a persistent goal only on a direct current human request. " +
                    "Quote that request in user_request. Defaults use the user's selected budget; " +
                    "override only if requested. " +
                    "Execution begins after this turn settles. Use get_goal first; " +
                    "only one unfinished goal is allowed.",
                "get_goal" to "Read this session's current goal, revision, pending changes and cumulative budgets. " +
                    "Call before update_goal; never use an old revision or another session's goal.",
                "update_goal" to "Update the goal read by get_goal using its exact id and expected_revision. " +
                    "Report complete, in_progress or blocked with a concrete summary after all other tools. " +
                    "Only a current human request permits objective/budget edits, active or paused. " +
                    "Quote it in user_request. Edits take effect after settlement. Automatic rounds may only report. " +
                    "A failed test is not blocked if you can repair it. No change grants tool permissions.",
            )
        descriptions.forEach { (name, description) ->
            val schema =
                when (name) {
                    "create_goal" -> createSchema
                    "update_goal" -> updateSchema
                    else -> """{"type":"object","properties":{},"additionalProperties":false}"""
                }
            val descriptor =
                ToolDescriptor(
                    name = ToolName(name),
                    version = ToolVersion(1),
                    description = description,
                    inputSchema = Json.parseToJsonElement(schema).jsonObject,
                    outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
                    operationClass =
                        if (name == "get_goal") ToolOperationClass.READ_ONLY else ToolOperationClass.METADATA,
                    baseRisk = RiskLevel.L0,
                    timeout = 5.seconds,
                    maxOutputBytes = 131072,
                    requiredCapabilities = emptySet(),
                    idempotency = if (name == "get_goal") Idempotency.IDEMPOTENT else Idempotency.NON_IDEMPOTENT,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    origin = ToolOrigin.BuiltInOrigin,
                )
            registry.register(descriptor)
            implementations.register(
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
