package com.helix.app.goal

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.storage.HelixStorage
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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/** Model-owned semantic report. The host validates ownership, never certifies its truth. */
internal object GoalReportTool {
    const val NAME = "goal.report"
    private val schema =
        Json
            .parseToJsonElement(
                """{
            "type":"object",
            "properties":{
                "status":{"type":"string","enum":["complete","in_progress","blocked"]},
                "summary":{"type":"string","minLength":1,"maxLength":4096}
            },
            "required":["status","summary"],"additionalProperties":false
        }""",
            ).jsonObject

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        storage: HelixStorage,
    ) {
        val descriptor =
            ToolDescriptor(
                name = ToolName(NAME),
                version = ToolVersion(1),
                description =
                    """
                    Report the current Goal status after checking your work.
                    You judge whether the objective is complete. Include results,
                    checks and remaining limitations in summary. Use in_progress
                    when useful work remains; blocked only if external help is needed.
                    Finish other tools before reporting, then explain the result.
                    This report does not grant permissions.
                    """.trimIndent().replace("\n", " "),
                inputSchema = schema,
                outputSchema = schema,
                operationClass = ToolOperationClass.READ_ONLY,
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
                @Suppress("ReturnCount") // Explicit ownership and cancellation rejection boundaries.
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                    val binding = call.turnId?.let { storage.goalTurnBindings.byTurn(it) }
                    val run = binding?.let { storage.goalRuns.resolve(it.runId) }
                    val active = run != null && run.endedAt == null
                    val owned =
                        binding != null &&
                            call.turnId?.let { storage.turns.resolve(it).sessionId == call.sessionId } == true
                    val running = run?.let { storage.goals.resolve(it.goalId).state == "RUNNING" } == true
                    if (!active || !owned || !running) {
                        return ToolExecutorResult.Failed("No active Goal for this request", sideEffectFree = true)
                    }
                    if (call.args
                            .getValue("summary")
                            .jsonPrimitive.content
                            .isBlank()
                    ) {
                        return ToolExecutorResult.Failed("Provide a nonblank summary", sideEffectFree = true)
                    }
                    return ToolExecutorResult.Completed(call.args)
                }
            },
        )
    }
}

internal data class GoalModelReport(
    val callId: String,
    val status: String,
    val summary: String,
)

/** Only the last tool in this Turn can report completion; later actions invalidate earlier reports. */
@Suppress("ReturnCount") // Reject stale, unsuccessful or malformed reports before consumption.
internal fun HelixStorage.goalModelReport(turnId: String): GoalModelReport? {
    val call = toolCalls.listByTurn(turnId).lastOrNull() ?: return null
    if (call.name != GoalReportTool.NAME || call.state != "COMPLETED") return null
    val result = toolResults.byToolCall(call.id) ?: return null
    if (result.status != "SUCCEEDED" || !result.verified) return null
    val args = Json.parseToJsonElement(call.argsJson).jsonObject
    val status = args["status"]?.jsonPrimitive?.content ?: return null
    val summary = args["summary"]?.jsonPrimitive?.content ?: return null
    if (status !in setOf("complete", "in_progress", "blocked") ||
        summary.isBlank() || summary.length > 4096
    ) {
        return null
    }
    return GoalModelReport(call.id, status, summary)
}
