package com.helix.app.chat

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object UserQuestionTool {
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName("ask_user"),
            version = ToolVersion(1),
            description = packagedPromptTemplates.text("ask-user").trim(),
            inputSchema =
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{
                "question":{"type":"string","minLength":1,"maxLength":1000},
                "options":{"type":"array","maxItems":6,
                "items":{"type":"string","minLength":1,"maxLength":200}},
                "multiple":{"type":"boolean"}},"required":["question"],"additionalProperties":false}""",
                    ).jsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = ToolOperationClass.METADATA,
            timeout = 5.seconds,
            maxOutputBytes = 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun register(
        registry: ToolRegistry,
        service: UserQuestionService,
    ) {
        val descriptor = descriptor()

        registry.register(
            descriptor,
            object : ToolExecutor {
                @Suppress("ReturnCount") // Cancellation and expired calls cannot create a question.
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                    if (java.time.Instant
                            .now()
                            .isAfter(call.deadline)
                    ) {
                        return ToolExecutorResult.TimedOut
                    }
                    val id = "question:${call.sessionId}:${call.turnId}:${call.toolCallId}"
                    service.offer(id, requireNotNull(call.sessionId), call.turnId, call.args)
                    return ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("questionId", id)
                            put("status", "AWAITING_ANSWER")
                            put(
                                "note",
                                "No answer yet. Continue independent work; " +
                                    "do not assume consent or repeat the question.",
                            )
                        },
                    )
                }
            },
        )
    }
}
