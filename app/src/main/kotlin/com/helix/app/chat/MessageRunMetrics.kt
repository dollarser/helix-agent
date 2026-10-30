package com.helix.app.chat

import com.helix.core.agent.ModelClientObservation
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ModelRequestMetric(
    val ordinal: Long,
    val state: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val observation: ModelClientObservation?,
) {
    /** Actual reported tokens divided by client request duration; not an inferred tokenizer or GPU speed. */
    val requestTokensPerSecond: Double?
        get() =
            observation?.durationMs?.takeIf { it > 0 }?.let { duration ->
                outputTokens?.toDouble()?.times(1000)?.div(duration)
            }
}

data class MessageRunMetrics(
    val requests: List<ModelRequestMetric>,
    val toolCalls: Int,
)

internal class MessageRunMetricsReader(
    private val storage: HelixStorage,
) {
    fun read(messageId: String): MessageRunMetrics {
        var result: MessageRunMetrics? = null
        storage.withTransaction {
            val message = storage.messages.resolve(messageId)
            require(message.role == "ASSISTANT")
            val turn = storage.turns.resolve(requireNotNull(message.turnId))
            require(turn.sessionId == message.sessionId)
            val requests =
                storage.modelCalls.listByTurn(turn.id).map { call ->
                    val usage =
                        call.usage?.let { raw ->
                            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                        }

                    fun tokens(key: String): Long? =
                        (usage?.get(key) as? JsonPrimitive)
                            ?.takeUnless { it.isString }
                            ?.longOrNull
                            ?.takeIf { it >= 0 }
                    val observed =
                        (usage?.get("clientObservation") as? JsonObject)?.let {
                            runCatching { ModelClientObservation.parse(it) }.getOrNull()
                        }
                    ModelRequestMetric(
                        ordinal = call.ordinal,
                        state = call.state,
                        inputTokens = tokens("inputTokens"),
                        outputTokens = tokens("outputTokens"),
                        observation = observed,
                    )
                }
            result = MessageRunMetrics(requests, storage.toolCalls.listByTurn(turn.id).size)
        }
        return requireNotNull(result)
    }
}
