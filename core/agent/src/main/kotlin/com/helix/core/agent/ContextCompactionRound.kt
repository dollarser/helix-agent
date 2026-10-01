package com.helix.core.agent

import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ModelStreamState
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ModelRequest
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Summary planning and commit decisions, separate from normal model/tool loop orchestration. */
class ContextCompactionRound(
    private val source: ContextCompactionSource,
    private val summaries: ContextSummaryFormat,
    private val control: RunControlConfig,
    private val settings: ProviderContextSettings,
    private val manual: Boolean,
    private val summaryReasoning: ReasoningEffort = ReasoningEffort.OFF,
) : com.helix.core.agent.ContextCompactionCycle {
    private var attempts = 0
    private var failures = 0
    private var bypassAt: Long? = null
    private var beforeSummary: TurnContextRequest? = null
    private var observedInput = 0L
    private var observedEstimate = 0L

    override fun observe(
        request: TurnContextRequest,
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

    override suspend fun admissionInput(request: TurnContextRequest): Long {
        val calibrated = (request.inputTokens() * inputScale).toLong()
        return maxOf(calibrated, source.inputFloor(request.model))
    }

    private suspend fun capacityFailure(request: TurnContextRequest): String? =
        ContextCapacity.failure(
            request.messages.size,
            admissionInput(request),
            request.maxOutputTokens,
            control.budgets.maxInputTokens,
            settings.window,
        )

    override suspend fun prepare(request: TurnContextRequest): com.helix.core.agent.PreparedContext {
        val bounded = request.copy(maxOutputTokens = minOf(request.maxOutputTokens, settings.window / 4))
        val calibratedTrigger = shouldCompact(bounded)
        var planningFailure: String? = null
        val plan =
            try {
                if (shouldAttempt(bounded)) {
                    source.plan(bounded, control, settings, manual || calibratedTrigger, inputScale)?.let {
                        it.copy(request = it.request.copy(reasoning = summaryReasoning))
                    }
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
        return com.helix.core.agent.PreparedContext(
            if (code == null) plan?.request ?: bounded.modelRequest() else null,
            plan,
            code?.let { ModelStreamTerminal(TurnState.FAILED, it) },
        )
    }

    override suspend fun finish(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
        journal: com.helix.core.agent.AgentTurnJournal,
        nextId: String,
        notice: String,
        unchangedNotice: String,
    ): ModelStreamTerminal? {
        val failure = validationFailure(plan, stream, decision)
        journal.recordDiagnostic(
            "context.compaction",
            kotlinx.serialization.json
                .buildJsonObject {
                    put("version", kotlinx.serialization.json.JsonPrimitive(1))
                    put("code", kotlinx.serialization.json.JsonPrimitive(failure?.errorCode ?: "COMPLETED"))
                    put("attempt", kotlinx.serialization.json.JsonPrimitive(attempts))
                    put("before", kotlinx.serialization.json.JsonPrimitive(plan.originalInputTokens))
                    if (failure == null || failure.errorCode == "CONTEXT_NO_GAIN") {
                        put(
                            "after",
                            kotlinx.serialization.json.JsonPrimitive(
                                summaries.summarizedRequest(plan, stream.text).inputTokens(),
                            ),
                        )
                    }
                }.toString(),
        )
        return if (manual && failure?.errorCode == "CONTEXT_NO_GAIN") {
            currentCoroutineContext().ensureActive()
            journal.commitCompaction(plan, null, unchangedNotice, saveSummary = false)
            ModelStreamTerminal(TurnState.COMPLETED, null)
        } else if (failure != null) {
            recover(plan, stream, failure, journal, nextId)
        } else {
            currentCoroutineContext().ensureActive()
            journal.commitCompaction(plan, if (manual) null else nextId, if (manual) notice else null)
            // Checkpoint removes the old absolute usage floor; retain the same-model calibration scale.
            if (manual) decision else null
        }
    }

    private fun shouldAttempt(request: TurnContextRequest): Boolean {
        val bypass = bypassAt?.let { request.inputTokens() < it + maxOf(256, it / 5) } == true
        return attempts < 2 && !bypass
    }

    private suspend fun shouldCompact(request: TurnContextRequest): Boolean =
        ContextCapacity.shouldCompact(request, settings, control.budgets.maxInputTokens, admissionInput(request))

    private fun validationFailure(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
    ): ModelStreamTerminal? =
        when {
            decision.state != TurnState.COMPLETED -> {
                decision
            }

            !stream.completed || stream.finishedToolCalls.isNotEmpty() || stream.text.isBlank() ||
                stream.text.length > ContextSummaryFormat.MAX_SUMMARY_CHARS -> {
                ModelStreamTerminal(
                    TurnState.FAILED,
                    "CONTEXT_SUMMARY_INVALID",
                )
            }

            else -> {
                if (!summaries.hasUsefulGain(plan, stream.text)) {
                    ModelStreamTerminal(TurnState.FAILED, "CONTEXT_NO_GAIN")
                } else {
                    null
                }
            }
        }

    @Suppress("ReturnCount") // Refusal/cancel, irreducible hard limit, and a bounded retry remain explicit.
    private suspend fun recover(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        failure: ModelStreamTerminal,
        journal: com.helix.core.agent.AgentTurnJournal,
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
        journal.commitCompaction(plan, nextId, failureReason = requireNotNull(failure.errorCode))
        return null
    }
}
