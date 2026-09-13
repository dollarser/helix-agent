package com.helix.app.agent

import com.helix.core.agent.TokenEstimator
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort

/** Unsent context can exceed wire message limits; compaction happens before wire validation. */
internal data class ChatContextRequest(
    val model: String,
    val messages: List<ModelMessage>,
    val tools: List<ModelToolSchema>,
    val maxOutputTokens: Long,
    val reasoning: ReasoningEffort,
) {
    fun modelRequest(): ModelRequest =
        ModelRequest(
            model,
            messages,
            tools,
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
        )

    fun inputTokens(): Long =
        TokenEstimator.estimateTokens(
            messages.sumOf { message ->
                message.text
                    .toByteArray()
                    .size
                    .toLong() +
                    message.toolCalls.sumOf {
                        it.argumentsJson
                            .toByteArray()
                            .size
                            .toLong()
                    }
            } +
                tools.sumOf {
                    it.description
                        .toByteArray()
                        .size
                        .toLong() +
                        it.inputSchemaJson
                            .toByteArray()
                            .size
                            .toLong()
                },
        ) + messages.size * 16L + tools.size * 32L + messages.sumOf { it.images.size * 2048L }
}
