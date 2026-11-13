package com.helix.core.agent

import com.helix.core.agent.TokenEstimator
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema

/** One conservative estimate for context admission, Turn admission and Goal reservations. */
data class ModelInputEstimate(
    val instructions: Long,
    val history: Long,
    val tools: Long,
    val images: Long,
) {
    val total: Long get() = instructions + history + tools + images

    companion object {
        private const val TOOL_FRAME_BYTES = 128L

        /** Final description/schema bytes plus the existing 32-token framing estimate, not wire bytes. */
        fun toolBytes(tool: ModelToolSchema): Long = toolBytes(tool.description, tool.inputSchemaJson)

        fun toolBytes(
            description: String,
            schema: String,
        ): Long = TokenEstimator.utf8Bytes(description) + TokenEstimator.utf8Bytes(schema) + TOOL_FRAME_BYTES

        fun of(request: ModelRequest): ModelInputEstimate = of(request.messages, request.tools)

        fun of(
            messages: List<ModelMessage>,
            tools: List<ModelToolSchema>,
        ): ModelInputEstimate {
            fun textTokens(rows: List<ModelMessage>): Long =
                TokenEstimator.estimateTokens(
                    rows.sumOf { row ->
                        TokenEstimator.utf8Bytes(row.modelText) +
                            row.toolCalls.sumOf {
                                TokenEstimator.utf8Bytes(it.argumentsJson)
                            }
                    },
                ) + rows.size * 16L
            return ModelInputEstimate(
                textTokens(messages.filter { it.role == ModelRole.SYSTEM }),
                textTokens(messages.filter { it.role != ModelRole.SYSTEM }),
                TokenEstimator.estimateTokens(tools.sumOf(::toolBytes)),
                messages.sumOf { it.images.size * 2048L },
            )
        }
    }
}
