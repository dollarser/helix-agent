package com.helix.app.session

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object SessionDelegationTool {
    fun register(
        registry: ToolRegistry,
        service: () -> SessionDelegationService,
    ) {
        val executor =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall) = SessionDelegationTool.execute(call, service())
            }
        registry.register(descriptor(false), executor)
        registry.register(descriptor(true), executor)
    }

    private fun execute(
        call: ExecutableToolCall,
        service: SessionDelegationService,
    ): ToolExecutorResult =
        try {
            checkActive(call)
            val receipt =
                runBlocking {
                    val caller =
                        SessionHistoryCaller(
                            requireNotNull(call.sessionId),
                            requireNotNull(call.turnId),
                            call.toolCallId,
                            call.toolName,
                        )
                    if (call.toolName == "session.steer") {
                        service.steer(
                            caller,
                            call.text("targetSessionId"),
                            call.text("expectedTurnId"),
                            call.text("requestKey"),
                            call.text("text"),
                            checkActive = { checkActive(call) },
                        )
                    } else {
                        service.queue(
                            caller,
                            call.text("targetSessionId"),
                            call.text("requestKey"),
                            call.text("text"),
                            checkActive = { checkActive(call) },
                        )
                    }
                }
            completed(receipt)
        } catch (_: DelegationStopped) {
            if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else ToolExecutorResult.TimedOut
        } catch (invalid: IllegalArgumentException) {
            val code =
                invalid.message?.takeIf {
                    it in
                        setOf(
                            "SESSION_REQUEST_CONFLICT",
                            "SESSION_DELEGATION_CYCLE",
                            "INPUT_QUEUE_FULL",
                            "INPUT_QUEUE_BYTES",
                            "INPUT_TARGET_UNAVAILABLE",
                            "INPUT_CONFIGURATION_CHANGED",
                        )
                } ?: "SESSION_DATA_UNAVAILABLE"
            ToolExecutorResult.Failed(code, sideEffectFree = true)
        }

    private fun completed(receipt: SessionDelegationService.Receipt) =
        ToolExecutorResult.Completed(
            buildJsonObject {
                put("inputId", receipt.record.inputId)
                put("targetSessionId", receipt.record.sessionId)
                put("requestKey", requireNotNull(receipt.record.delegation).requestKey)
                put("delivery", receipt.record.delivery.name)
                put("expectedTurnId", receipt.record.expectedTurnId)
                put("accepted", true)
                put("duplicate", receipt.duplicate)
                put("inputState", receipt.record.state.name)
                put("consumedTurnId", receipt.record.consumedTurnId)
                put("requestModelCallId", receipt.record.requestModelCallId)
                put("targetTurnState", receipt.targetTurnState)
                put("blockedReason", receipt.record.blockedReason)
            },
            auditDetail = buildJsonObject { put("observerNotified", receipt.observerNotified) },
        )

    private fun checkActive(call: ExecutableToolCall) {
        if (call.cancel.isCancelled() ||
            !java.time.Instant
                .now()
                .isBefore(call.deadline)
        ) {
            throw DelegationStopped()
        }
    }

    private fun ExecutableToolCall.text(key: String) = args.getValue(key).jsonPrimitive.content

    private class DelegationStopped : java.util.concurrent.CancellationException()

    private fun descriptor(steer: Boolean) =
        ToolDescriptor(
            name = ToolName(if (steer) "session.steer" else "session.queue"),
            version = ToolVersion(1),
            description =
                (
                    if (steer) {
                        "Steer exactly expectedTurnId in a target conversation " +
                            "explicitly granted delegateTasks by the user. " +
                            "Reject a finished or changed Turn; never fall back to Queue or start a new Turn. "
                    } else {
                        "Queue a task for a new Turn in a target conversation " +
                            "explicitly granted delegateTasks by the user. " +
                            "Does not interrupt active work. "
                    }
                ) +
                    "The target uses its own current permissions and normal approvals. " +
                    "Retry with the same requestKey and exact delivery kind, target, expected Turn and text. " +
                    "Receipt state is delivery/Turn state, not verified task success. " +
                    "No automatic forwarding or reply loop; delegation back into the durable ancestry is rejected.",
            inputSchema = schema(steer),
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            timeout = 10.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun schema(steer: Boolean) =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{
          "targetSessionId":{"type":"string","minLength":1,"maxLength":256},
          "requestKey":{"type":"string","minLength":1,"maxLength":128},
          ${if (steer) "\"expectedTurnId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128}," else ""}
          "text":{"type":"string","minLength":1,"maxLength":128000}},
          "required":["targetSessionId","requestKey","text"${if (steer) ",\"expectedTurnId\"" else ""}],
          "additionalProperties":false}""",
            ).jsonObject
}
