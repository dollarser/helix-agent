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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object SessionCreationTool {
    fun register(
        registry: ToolRegistry,
        service: () -> SessionCreationService,
    ) {
        registry.register(
            descriptor(),
            object : ToolExecutor {
                // Cancel/timeout stop before the transaction; committed receipts remain authoritative.
                @Suppress("ReturnCount")
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                    if (!java.time.Instant
                            .now()
                            .isBefore(call.deadline)
                    ) {
                        return ToolExecutorResult.TimedOut
                    }
                    return try {
                        val receipt =
                            service().create(
                                SessionHistoryCaller(
                                    requireNotNull(call.sessionId),
                                    requireNotNull(call.turnId),
                                    call.toolCallId,
                                    call.toolName,
                                ),
                                call.args
                                    .getValue("requestKey")
                                    .jsonPrimitive.content,
                                call.args
                                    .getValue("title")
                                    .jsonPrimitive.content,
                                checkActive = {
                                    if (call.cancel.isCancelled() ||
                                        !java.time.Instant
                                            .now()
                                            .isBefore(call.deadline)
                                    ) {
                                        throw CreationStopped()
                                    }
                                },
                            )
                        // Cancellation after this durable commit must not obscure the receipt with failure.
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("sessionId", receipt.createdSessionId)
                                put("requestKey", receipt.requestKey)
                                put("createdAt", receipt.createdAt)
                                put("started", false)
                                put("accessGranted", false)
                            },
                        )
                    } catch (_: CreationStopped) {
                        if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else ToolExecutorResult.TimedOut
                    } catch (invalid: IllegalArgumentException) {
                        val code =
                            invalid.message?.takeIf {
                                it in setOf("SESSION_CREATION_NOT_ALLOWED", "SESSION_REQUEST_CONFLICT")
                            } ?: "SESSION_CREATION_UNAVAILABLE"
                        ToolExecutorResult.Failed(code, sideEffectFree = true)
                    }
                }
            },
        )
    }

    private class CreationStopped : java.util.concurrent.CancellationException()

    private fun descriptor() =
        ToolDescriptor(
            name = ToolName("session.create"),
            version = ToolVersion(1),
            description =
                "Create an empty local conversation only when the user enabled creation for this source. " +
                    "Does not start work, copy configuration or grants, or grant access to the new conversation. " +
                    "Use a stable requestKey for retries: the same key/title returns the original receipt, " +
                    "a different title conflicts. A receipt does not mean the target still exists.",
            inputSchema =
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{
              "requestKey":{"type":"string","minLength":1,"maxLength":128},
              "title":{"type":"string","minLength":1,"maxLength":200}},
              "required":["requestKey","title"],"additionalProperties":false}""",
                    ).jsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            timeout = 5.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )
}
