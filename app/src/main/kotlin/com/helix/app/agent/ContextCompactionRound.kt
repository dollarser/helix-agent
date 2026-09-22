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
        if (actualInput != null || observedEstimate == 0L) {
            observedEstimate = request.inputTokens()
            observedInput = actualInput ?: observedEstimate
        }
        attempts = 0
        failures = 0
    }

    private val inputScale: Double
        get() = if (observedEstimate > 0) maxOf(1.0, observedInput.toDouble() / observedEstimate) else 1.0

    fun admissionInput(request: ChatContextRequest): Long {
        val calibrated = (request.inputTokens() * inputScale).toLong()
        return maxOf(calibrated, ContextPressure.inputFloor(storage, sessionId, turnId, request.model))
    }

    private fun capacityFailure(request: ChatContextRequest): String? =
        ContextCapacity.failure(
            request.messages.size,
            admissionInput(request),
            request.maxOutputTokens,
            control.budgets.maxInputTokens,
            settings.window,
        )

    data class Prepared(
        val request: ModelRequest?,
        val plan: ContextCompaction.Plan?,
        val failure: ModelStreamTerminal?,
    )

    fun prepare(request: ChatContextRequest): Prepared {
        val bounded = request.copy(maxOutputTokens = minOf(request.maxOutputTokens, settings.window / 4))
        val calibratedTrigger = shouldCompact(bounded)
        var planningFailure: String? = null
        val plan =
            try {
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
                            inputScale,
                        )?.let { it.copy(request = it.request.copy(reasoning = summaryReasoning)) }
                } else {
                    null
                }
            } catch (failure: ContextCapacityException) {
                planningFailure = failure.code
                null
            }
        if (plan != null) {
            attempts++
            beforeSummary = bounded
        }
        val code =
            if (plan == null) {
                ContextCapacity.withoutSummary(manual, planningFailure, capacityFailure(bounded))
            } else {
                ContextCapacity.forSummary(
                    plan.request,
                    observedInput,
                    observedEstimate,
                    control.budgets,
                    settings.window,
                )
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
        coordinator.recordDiagnostic(
            "context.compaction",
            kotlinx.serialization.json
                .buildJsonObject {
                    put("version", kotlinx.serialization.json.JsonPrimitive(1))
                    put("code", kotlinx.serialization.json.JsonPrimitive(failure?.errorCode ?: "COMPLETED"))
                    put("attempt", kotlinx.serialization.json.JsonPrimitive(attempts))
                }.toString(),
        )
        if (failure != null) return recover(plan, stream, failure, coordinator, nextId)
        currentCoroutineContext().ensureActive()
        coordinator.commitCompaction(plan, if (manual) null else nextId, if (manual) notice else null)
        // Checkpoint removes the old absolute usage floor; retain the same-model calibration scale.
        return if (manual) decision else null
    }

    private fun shouldAttempt(request: ChatContextRequest): Boolean {
        val bypass = bypassAt?.let { request.inputTokens() < it + maxOf(256, it / 5) } == true
        return attempts < 2 && !bypass
    }

    private fun shouldCompact(request: ChatContextRequest): Boolean =
        ContextCapacity.shouldCompact(request, settings, control.budgets.maxInputTokens, admissionInput(request))

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
            capacityFailure(original)?.let { return ModelStreamTerminal(TurnState.FAILED, it) }
            bypassAt = original.inputTokens()
        }
        coordinator.commitCompaction(plan, nextId, failureReason = requireNotNull(failure.errorCode))
        return null
    }
}
