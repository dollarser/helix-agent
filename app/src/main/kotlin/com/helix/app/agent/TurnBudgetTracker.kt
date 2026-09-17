package com.helix.app.agent

import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnBudgets

/** Production Turn accounting; unknown provider usage is conservatively byte-estimated. */
internal class TurnBudgetTracker(
    private val budgets: TurnBudgets,
) {
    private var modelCalls = 0
    private var totalTokens = 0L

    val consumedTokens: Long get() = totalTokens
    val consumedCalls: Int get() = modelCalls
    var lastFailureCode: String? = null
        private set

    data class CallAdmission(
        val decision: BeginDecision,
        val request: ModelRequest? = null,
    )

    /** Bind the transport request before spending a call; no positive output headroom means no request. */
    fun prepareCall(request: ModelRequest): CallAdmission {
        val estimatedInput = ModelInputEstimate.of(request).total
        val remaining = budgets.maxTotalTokens - totalTokens
        val output =
            minOf(
                request.maxOutputTokens ?: Long.MAX_VALUE,
                budgets.maxOutputTokens,
                remaining - estimatedInput,
            )
        return when {
            modelCalls >= budgets.maxModelCalls -> {
                CallAdmission(BeginDecision.MODEL_CALL_LIMIT)
            }

            estimatedInput > budgets.maxInputTokens -> {
                CallAdmission(BeginDecision.INPUT_LIMIT)
            }

            output < 1 -> {
                CallAdmission(BeginDecision.TOKEN_LIMIT)
            }

            else -> {
                modelCalls += 1
                CallAdmission(BeginDecision.ALLOWED, request.copy(maxOutputTokens = output))
            }
        }
    }

    fun finishCall(
        callId: String,
        request: ModelRequest,
        stream: ModelStreamState,
    ): Boolean {
        val account = ModelCallUsage.account(callId, request, stream)
        val callTotal = ModelCallUsage.total(account)
        val remaining = budgets.maxTotalTokens - totalTokens
        lastFailureCode =
            when {
                callTotal > remaining -> {
                    "TURN_TOTAL_TOKEN_LIMIT"
                }

                account.effectiveInput > budgets.maxInputTokens -> {
                    "INPUT_TOKEN_LIMIT"
                }

                account.effectiveOutput > minOf(budgets.maxOutputTokens, request.maxOutputTokens ?: Long.MAX_VALUE) -> {
                    "OUTPUT_TOKEN_LIMIT"
                }

                else -> {
                    null
                }
            }
        totalTokens = if (callTotal > Long.MAX_VALUE - totalTokens) Long.MAX_VALUE else totalTokens + callTotal
        return lastFailureCode == null
    }

    enum class BeginDecision { ALLOWED, MODEL_CALL_LIMIT, INPUT_LIMIT, TOKEN_LIMIT }

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
