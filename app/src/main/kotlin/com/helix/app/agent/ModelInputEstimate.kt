package com.helix.app.agent

import com.helix.core.agent.TokenEstimator
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema

/** One conservative estimate for context admission, Turn admission and Goal reservations. */
internal data class ModelInputEstimate(
    val instructions: Long,
    val history: Long,
    val tools: Long,
    val images: Long,
) {
    val total: Long get() = instructions + history + tools + images

    companion object {
        fun of(request: ModelRequest): ModelInputEstimate = of(request.messages, request.tools)

        fun of(
            messages: List<ModelMessage>,
            tools: List<ModelToolSchema>,
        ): ModelInputEstimate {
            fun textTokens(rows: List<ModelMessage>): Long =
                TokenEstimator.estimateTokens(
                    rows.sumOf { row ->
                        TokenEstimator.utf8Bytes(row.text) +
                            row.toolCalls.sumOf {
                                TokenEstimator.utf8Bytes(it.argumentsJson)
                            }
                    },
                ) + rows.size * 16L
            return ModelInputEstimate(
                textTokens(messages.filter { it.role == ModelRole.SYSTEM }),
                textTokens(messages.filter { it.role != ModelRole.SYSTEM }),
                TokenEstimator.estimateTokens(
                    tools.sumOf {
                        TokenEstimator.utf8Bytes(it.description) + TokenEstimator.utf8Bytes(it.inputSchemaJson)
                    },
                ) + tools.size * 32L,
                messages.sumOf { it.images.size * 2048L },
            )
        }
    }
}
