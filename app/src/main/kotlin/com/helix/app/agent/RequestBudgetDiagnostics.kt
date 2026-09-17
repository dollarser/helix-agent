package com.helix.app.agent

import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnBudgets
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded numeric metadata, never prompt, result, endpoint or credential content. */
internal object RequestBudgetDiagnostics {
    fun admitted(
        request: ModelRequest,
        compacting: Boolean,
    ): String =
        buildJsonObject {
            put("version", 1)
            put("kind", if (compacting) "summary" else "task")
            put("source", "estimated")
            put("input", ModelInputEstimate.of(request).total)
            put("outputLimit", request.maxOutputTokens)
        }.toString()

    fun request(
        context: ChatContextRequest,
        budgets: TurnBudgets,
        window: Long,
        tracker: TurnBudgetTracker,
        admissionInput: Long = context.inputTokens(),
    ): String {
        val estimate = ModelInputEstimate.of(context.messages, context.tools)
        return buildJsonObject {
            put("version", 1)
            put("source", "estimated")
            put("kind", "context")
            put("instructions", estimate.instructions)
            put("history", estimate.history)
            put("tools", estimate.tools)
            put("images", estimate.images)
            put("input", estimate.total)
            put("admissionInput", admissionInput)
            put("inputLimit", budgets.maxInputTokens)
            put("outputLimit", minOf(context.maxOutputTokens, window / 4))
            put("window", window)
            put("used", tracker.consumedTokens)
            put("totalLimit", budgets.maxTotalTokens)
            put("calls", tracker.consumedCalls)
            put("callLimit", budgets.maxModelCalls)
            put("roundLimit", budgets.maxSteps)
        }.toString()
    }

    fun result(
        code: String?,
        stream: ModelStreamState?,
        tracker: TurnBudgetTracker,
    ): String =
        buildJsonObject {
            put("version", 1)
            put("code", code ?: "COMPLETED")
            put("inputSource", if (stream?.inputTokens != null) "reported" else "estimated")
            put("outputSource", if (stream?.outputTokens != null) "reported" else "estimated")
            stream?.inputTokens?.let { put("input", it) }
            stream?.outputTokens?.let { put("output", it) }
            put("used", tracker.consumedTokens)
            put("calls", tracker.consumedCalls)
        }.toString()
}
