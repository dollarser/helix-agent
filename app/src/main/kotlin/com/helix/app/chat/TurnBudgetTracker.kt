package com.helix.app.chat

import com.helix.core.agent.CallTokenAccount
import com.helix.core.model.ModelCallId
import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnBudgets

/** Production Turn accounting; unknown provider usage is conservatively byte-estimated. */
internal class TurnBudgetTracker(
    private val budgets: TurnBudgets,
) {
    private var modelCalls = 0
    private var totalTokens = 0L

    fun beginCall(request: ModelRequest): BeginDecision {
        val estimatedInput =
            com.helix.core.agent.TokenEstimator
                .estimateTokens(requestSizeBytes(request))
        return when {
            modelCalls >= budgets.maxModelCalls -> {
                BeginDecision.MODEL_CALL_LIMIT
            }

            estimatedInput > budgets.maxInputTokens || estimatedInput > budgets.maxTotalTokens - totalTokens -> {
                BeginDecision.TOKEN_LIMIT
            }

            else -> {
                modelCalls += 1
                BeginDecision.ALLOWED
            }
        }
    }

    fun finishCall(
        callId: String,
        request: ModelRequest,
        stream: ModelStreamState,
    ): Boolean {
        val account =
            CallTokenAccount(
                callId = ModelCallId(callId),
                requestBytes = requestSizeBytes(request),
                responseBytes =
                    stream.text
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong(),
                inputTokens = stream.inputTokens,
                outputTokens = stream.outputTokens,
            )
        val callTotal =
            if (account.effectiveInput > Long.MAX_VALUE - account.effectiveOutput) {
                Long.MAX_VALUE
            } else {
                account.effectiveInput + account.effectiveOutput
            }
        val remaining = budgets.maxTotalTokens - totalTokens
        if (callTotal > remaining) return false
        totalTokens += callTotal
        return account.effectiveInput <= budgets.maxInputTokens && account.effectiveOutput <= budgets.maxOutputTokens
    }

    enum class BeginDecision { ALLOWED, MODEL_CALL_LIMIT, TOKEN_LIMIT }

    companion object {
        internal fun requestSizeBytes(request: ModelRequest): Long =
            request.messages.sumOf { message ->
                message.text
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong() +
                    message.toolCalls.sumOf {
                        it.argumentsJson
                            .toByteArray(Charsets.UTF_8)
                            .size
                            .toLong()
                    }
            } +
                request.tools.sumOf {
                    it.description
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong() +
                        it.inputSchemaJson
                            .toByteArray(Charsets.UTF_8)
                            .size
                            .toLong()
                }
    }
}
