package com.helix.app.agent

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole

/** Shared by task and summary admission; inputs remain estimates, not exact provider tokenization. */
internal object ContextCapacity {
    /** History already includes its system prompt, summaries and recovery notices. */
    fun forContinuation(
        assembled: ChatContextRequest,
        text: String,
        inputLimit: Long,
        window: Long,
    ): String? {
        val request =
            assembled.copy(
                messages = assembled.messages + ModelMessage(ModelRole.USER, text),
            )
        return failure(
            request.messages.size,
            request.inputTokens(),
            minOf(request.maxOutputTokens, window / 4),
            inputLimit,
            window,
        )
    }

    fun shouldCompact(
        request: ChatContextRequest,
        settings: com.helix.app.provider.ProviderContextSettings,
        inputLimit: Long,
        input: Long,
    ): Boolean =
        settings.autoCompact && (
            input >= settings.window * settings.triggerPercent / 100 - request.maxOutputTokens ||
                input > inputLimit || request.messages.size >= ModelRequest.MAX_MESSAGES - 16
        )

    fun withoutSummary(
        manual: Boolean,
        planningFailure: String?,
        capacityFailure: String?,
    ): String? =
        when {
            manual -> planningFailure ?: "CONTEXT_NOT_COMPACTABLE"
            capacityFailure != null -> planningFailure ?: capacityFailure
            else -> null
        }

    fun forSummary(
        request: ModelRequest,
        observedInput: Long,
        observedEstimate: Long,
        budgets: com.helix.core.model.TurnBudgets,
        window: Long,
    ): String? {
        val estimate = ModelInputEstimate.of(request).total
        val ratio = if (observedEstimate > 0) maxOf(1.0, observedInput.toDouble() / observedEstimate) else 1.0
        return failure(
            request.messages.size,
            (estimate * ratio).toLong(),
            request.maxOutputTokens ?: 0,
            budgets.maxInputTokens,
            window,
        )
    }

    fun failure(
        messages: Int,
        input: Long,
        output: Long,
        inputLimit: Long,
        window: Long,
    ): String? =
        when {
            messages > ModelRequest.MAX_MESSAGES -> "CONTEXT_MESSAGE_LIMIT"
            input > inputLimit -> "INPUT_TOKEN_LIMIT"
            output > window || input > window - output -> "CONTEXT_WINDOW_LIMIT"
            else -> null
        }
}
