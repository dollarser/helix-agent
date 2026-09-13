package com.helix.app.agent

import com.helix.app.provider.ProviderContextSettings
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.model.ModelRequest
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Summary planning and commit decisions, separate from normal model/tool loop orchestration. */
internal class ContextCompactionRound(
    private val storage: HelixStorage,
    private val sessionId: String,
    private val turnId: String,
    private val control: RunControlConfig,
    private val settings: ProviderContextSettings,
    private val manual: Boolean,
    private val summaryReasoning: ReasoningEffort = ReasoningEffort.OFF,
) {
    private var attempts = 0
    private var failures = 0
    private var bypassAt: Long? = null
    private var beforeSummary: ChatContextRequest? = null
    private var observedInput = 0L
    private var observedEstimate = 0L

    fun observe(
        request: ChatContextRequest,
        actualInput: Long?,
    ) {
        observedEstimate = request.inputTokens()
        observedInput = actualInput ?: observedEstimate
        attempts = 0
        failures = 0
    }

    private fun pressure(request: ChatContextRequest): Long {
        val estimate = request.inputTokens()
        val calibrated =
            if (observedEstimate > 0) {
                (estimate.toDouble() * maxOf(1.0, observedInput.toDouble() / observedEstimate)).toLong()
            } else {
                estimate
            }
        return maxOf(calibrated, ContextPressure.inputFloor(storage, sessionId, turnId, request.model)) +
            request.maxOutputTokens
    }

    private fun fits(request: ChatContextRequest): Boolean =
        pressure(request) <= settings.window &&
            pressure(request) - request.maxOutputTokens <= control.budgets.maxInputTokens &&
            request.messages.size <= ModelRequest.MAX_MESSAGES

    data class Prepared(
        val request: ModelRequest?,
        val plan: ContextCompaction.Plan?,
        val failure: ModelStreamTerminal?,
    )

    fun prepare(request: ChatContextRequest): Prepared {
        val bounded = request.copy(maxOutputTokens = minOf(request.maxOutputTokens, settings.window / 4))
        val calibratedTrigger = shouldCompact(bounded)
        val plan =
            if (shouldAttempt(bounded)) {
                ContextCompaction
                    .plan(
                        storage,
                        sessionId,
                        bounded,
                        control,
                        settings,
                        manual || calibratedTrigger,
                        turnId,
                    )?.let { it.copy(request = it.request.copy(reasoning = summaryReasoning)) }
            } else {
                null
            }
        if (plan != null) {
            attempts++
            beforeSummary = bounded
        }
        val code =
            when {
                manual && plan == null -> "CONTEXT_NOT_COMPACTABLE"
                plan == null && !fits(bounded) -> "CONTEXT_WINDOW_LIMIT"
                else -> null
            }
        return Prepared(
            if (code == null) plan?.request ?: bounded.modelRequest() else null,
            plan,
            code?.let { ModelStreamTerminal(TurnState.FAILED, it) },
        )
    }

    suspend fun finish(
        plan: ContextCompaction.Plan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
        coordinator: TurnCoordinator,
        nextId: String,
        notice: String,
    ): ModelStreamTerminal? {
        val failure = validationFailure(plan, stream, decision)
        if (failure != null) return recover(plan, stream, failure, coordinator, nextId)
        currentCoroutineContext().ensureActive()
        coordinator.commitCompaction(plan, if (manual) null else nextId, if (manual) notice else null)
        observedInput = 0
        observedEstimate = 0
        return if (manual) decision else null
    }

    private fun shouldAttempt(request: ChatContextRequest): Boolean {
        val bypass = bypassAt?.let { request.inputTokens() < it + maxOf(256, it / 5) } == true
        return attempts < 2 && !bypass
    }

    private fun shouldCompact(request: ChatContextRequest): Boolean {
        val total = pressure(request)
        return settings.autoCompact &&
            (
                total >= settings.window * settings.triggerPercent / 100 ||
                    total - request.maxOutputTokens > control.budgets.maxInputTokens
            )
    }

    private fun validationFailure(
        plan: ContextCompaction.Plan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
    ): ModelStreamTerminal? =
        when {
            decision.state != TurnState.COMPLETED -> {
                decision
            }

            !stream.completed || stream.finishedToolCalls.isNotEmpty() || stream.text.isBlank() ||
                stream.text.length > ContextCompaction.MAX_SUMMARY_CHARS -> {
                ModelStreamTerminal(
                    TurnState.FAILED,
                    "CONTEXT_SUMMARY_INVALID",
                )
            }

            else -> {
                if (!ContextCompaction.hasUsefulGain(plan, stream.text)) {
                    ModelStreamTerminal(TurnState.FAILED, "CONTEXT_NO_GAIN")
                } else {
                    null
                }
            }
        }

    @Suppress("ReturnCount") // Refusal/cancel, irreducible hard limit, and a bounded retry remain explicit.
    private suspend fun recover(
        plan: ContextCompaction.Plan,
        stream: ModelStreamState,
        failure: ModelStreamTerminal,
        coordinator: TurnCoordinator,
        nextId: String,
    ): ModelStreamTerminal? {
        val transientProviderFailure =
            stream.retryableError && failure.errorCode in setOf("TRANSPORT", "TIMEOUT", "SERVER_ERROR")
        val recoverable =
            (failure.errorCode in setOf("CONTEXT_NO_GAIN", "CONTEXT_SUMMARY_INVALID") || transientProviderFailure) &&
                stream.finishedToolCalls.isEmpty() && failure.state != TurnState.CANCELLED
        if (manual || !recoverable) return failure
        currentCoroutineContext().ensureActive()
        failures++
        val original = requireNotNull(beforeSummary)
        if (failures > 1 || attempts >= 2) {
            if (!fits(original)) return ModelStreamTerminal(TurnState.FAILED, "CONTEXT_WINDOW_LIMIT")
            bypassAt = original.inputTokens()
        }
        coordinator.commitCompaction(plan, nextId, failureReason = requireNotNull(failure.errorCode))
        return null
    }
}
