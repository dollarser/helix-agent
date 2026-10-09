package com.helix.core.agent

import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnBudgets

/** Production Turn accounting; unknown provider usage is conservatively byte-estimated. */
class TurnBudgetTracker(
    private val budgets: TurnBudgets,
    initialModelCalls: Int = 0,
    initialTotalTokens: Long = 0,
) {
    private var modelCalls = initialModelCalls
    private var totalTokens = initialTotalTokens

    init {
        require(initialModelCalls >= 0 && initialTotalTokens >= 0)
    }

    val consumedTokens: Long get() = totalTokens
    val consumedCalls: Int get() = modelCalls
    var lastFailureCode: String? = null
        private set

    data class CallAdmission(
        val decision: BeginDecision,
        val request: ModelRequest? = null,
    )

    /** Usage is observed, not a cumulative execution allowance. */
    fun prepareCall(request: ModelRequest): CallAdmission {
        modelCalls = if (modelCalls == Int.MAX_VALUE) modelCalls else modelCalls + 1
        return CallAdmission(
            BeginDecision.ALLOWED,
            request.copy(
                maxOutputTokens = minOf(request.maxOutputTokens ?: budgets.maxOutputTokens, budgets.maxOutputTokens),
            ),
        )
    }

    fun finishCall(
        callId: String,
        request: ModelRequest,
        stream: ModelStreamState,
    ): Boolean {
        val account = ModelCallUsage.account(callId, request, stream)
        val callTotal = ModelCallUsage.total(account)
        lastFailureCode =
            if (account.effectiveOutput > minOf(budgets.maxOutputTokens, request.maxOutputTokens ?: Long.MAX_VALUE)) {
                "OUTPUT_TOKEN_LIMIT"
            } else {
                null
            }
        totalTokens = if (callTotal > Long.MAX_VALUE - totalTokens) Long.MAX_VALUE else totalTokens + callTotal
        return lastFailureCode == null
    }

    enum class BeginDecision { ALLOWED, MODEL_CALL_LIMIT, INPUT_LIMIT, TOKEN_LIMIT }

    companion object {
        fun restore(
            budgets: TurnBudgets,
            consumedModelCalls: Int,
            consumedTokens: Long,
        ): TurnBudgetTracker = TurnBudgetTracker(budgets, consumedModelCalls, consumedTokens)

        fun requestSizeBytes(request: ModelRequest): Long =
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
