package com.helix.core.agent

import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnState
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Summary planning and commit decisions, separate from normal model/tool loop orchestration. */
class ContextCompactionRound(
    private val source: ContextCompactionSource,
    private val summaries: ContextSummaryFormat,
    private val settings: ProviderContextSettings,
    private val manual: Boolean,
    private val summaryReasoning: ReasoningEffort = ReasoningEffort.OFF,
) : ContextCompactionCycle {
    private var attempts = 0
    private var pendingFailure: String? = null
    private var terminalFailure: ModelStreamTerminal? = null
    private var summaryCommitted = false
    private var observedInput = 0L
    private var observedEstimate = 0L
    private var summaryInputScale = 1.0

    override fun observe(
        request: TurnContextRequest,
        actualInput: Long?,
    ) {
        if (actualInput != null || observedEstimate == 0L) {
            observedEstimate = request.inputTokens()
            observedInput = actualInput ?: observedEstimate
        }
        attempts = 0
        pendingFailure = null
        terminalFailure = null
        summaryCommitted = false
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
            com.helix.provider.api.ProviderCapabilities.MAX_CONTEXT_BOUND,
            settings.window,
        )

    @Suppress("ReturnCount") // Terminal latch, projection failure, successful continuation and summary admission.
    override suspend fun prepare(request: TurnContextRequest): PreparedContext {
        currentCoroutineContext().ensureActive()
        terminalFailure?.let { return PreparedContext(null, null, it, request) }
        val bounded =
            try {
                ContextCompiler.projectContent(
                    request.copy(maxOutputTokens = minOf(request.maxOutputTokens, settings.window / 4)),
                    settings,
                    inputScale,
                )
            } catch (failure: ContextCapacityException) {
                return failedPreparation(request, failure.code)
            }
        val hardFailure = capacityFailure(bounded)
        // A successful checkpoint permits progress even if the pressure trigger remains true.
        // Only an ordinary model call resets this allowance; the hard window is still enforced.
        if (summaryCommitted) {
            return if (hardFailure == null) {
                PreparedContext(bounded.modelRequest(), null, null, bounded)
            } else {
                failedPreparation(bounded, hardFailure)
            }
        }
        return prepareSummary(bounded, hardFailure)
    }

    private suspend fun prepareSummary(
        request: TurnContextRequest,
        hardFailure: String?,
    ): PreparedContext {
        val required = manual || pendingFailure != null || shouldCompact(request)
        val summaryScale = maxOf(inputScale, summaryInputScale)
        var planningFailure: String? = null
        val plan =
            try {
                if (attempts < MAX_ATTEMPTS) {
                    source.plan(request, settings, required, summaryScale)?.let {
                        it.copy(request = it.request.copy(reasoning = summaryReasoning))
                    }
                } else {
                    null
                }
            } catch (failure: ContextCapacityException) {
                planningFailure = failure.code
                null
            }
        val code =
            if (plan == null) {
                planningFailure ?: pendingFailure?.let(::summaryTerminalCode) ?: hardFailure
                    ?: "CONTEXT_NOT_COMPACTABLE".takeIf { required || attempts >= MAX_ATTEMPTS }
            } else {
                val summaryEstimate = ModelInputEstimate.of(plan.request).total
                ContextCapacity.forSummary(
                    plan.request,
                    kotlin.math.ceil(summaryEstimate * summaryScale).toLong(),
                    summaryEstimate,
                    settings.window,
                )
            }
        if (code != null) return failedPreparation(request, code)
        if (plan != null) attempts++
        return PreparedContext(plan?.request ?: request.modelRequest(), plan, null, request)
    }

    private fun failedPreparation(
        request: TurnContextRequest,
        code: String,
    ): PreparedContext {
        val failure = ModelStreamTerminal(TurnState.FAILED, code)
        terminalFailure = failure
        return PreparedContext(null, null, failure, request)
    }

    override suspend fun finish(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
        journal: AgentTurnJournal,
        nextId: String,
        notice: String,
        unchangedNotice: String,
    ): ModelStreamTerminal? {
        val estimate = ModelInputEstimate.of(plan.request).total
        stream.inputTokens?.takeIf { estimate > 0 }?.let {
            summaryInputScale = maxOf(summaryInputScale, it.toDouble() / estimate)
        }
        val failure = validationFailure(plan, stream, decision)
        journal.recordDiagnostic(
            "context.compaction",
            buildJsonObject {
                put("code", failure?.errorCode ?: "COMPLETED")
                put("attempt", attempts)
                put("before", plan.originalInputTokens)
                if (failure == null || failure.errorCode == "CONTEXT_NO_GAIN") {
                    put("after", summaries.summarizedRequest(plan, stream.text).inputTokens())
                }
            }.toString(),
        )
        return if (failure != null) {
            recover(plan, stream, failure, journal, nextId)
        } else {
            currentCoroutineContext().ensureActive()
            journal.commitCompaction(plan, if (manual) null else nextId, if (manual) notice else null)
            // Checkpoint removes the old absolute usage floor; retain the same-model calibration scale.
            summaryCommitted = true
            pendingFailure = null
            if (manual) decision else null
        }
    }

    private suspend fun shouldCompact(request: TurnContextRequest): Boolean =
        ContextCapacity.shouldCompact(
            request,
            settings,
            com.helix.provider.api.ProviderCapabilities.MAX_CONTEXT_BOUND,
            admissionInput(request),
        )

    private fun validationFailure(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
    ): ModelStreamTerminal? =
        when {
            decision.state != TurnState.COMPLETED -> {
                decision
            }

            !stream.completed || stream.hasToolCallFragments || stream.text.isBlank() ||
                stream.text.length > ContextSummaryFormat.MAX_SUMMARY_CHARS ||
                ContextSummaryText.isToolCallOnly(stream.text) -> {
                ModelStreamTerminal(TurnState.FAILED, "CONTEXT_SUMMARY_INVALID")
            }

            !summaries.hasUsefulGain(plan, stream.text) -> {
                ModelStreamTerminal(TurnState.FAILED, "CONTEXT_NO_GAIN")
            }

            else -> {
                null
            }
        }

    private suspend fun recover(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        failure: ModelStreamTerminal,
        journal: AgentTurnJournal,
        nextId: String,
    ): ModelStreamTerminal? {
        val retryableEmptyFailure =
            stream.retryableError && !stream.completed && !stream.receiving &&
                failure.errorCode != ModelStreamState.REFUSAL
        val recoverable =
            failure.state == TurnState.FAILED && !stream.hasToolCallFragments &&
                (failure.errorCode in RETRYABLE_FAILURES || retryableEmptyFailure)
        if (recoverable) {
            currentCoroutineContext().ensureActive()
            val code = requireNotNull(failure.errorCode)
            pendingFailure = code
            // Close every failed summary call with its usage, without publishing its text or history.
            journal.commitCompaction(plan, nextId.takeIf { attempts < MAX_ATTEMPTS }, failureReason = code)
            if (attempts < MAX_ATTEMPTS) return null
        }
        val terminal = failure.copy(errorCode = failure.errorCode?.let(::summaryTerminalCode))
        terminalFailure = terminal
        return terminal
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val RETRYABLE_FAILURES = setOf("CONTEXT_NO_GAIN", "CONTEXT_SUMMARY_INVALID", "OUTPUT_TOKEN_LIMIT")
    }
}

private fun summaryTerminalCode(code: String): String =
    if (code == "OUTPUT_TOKEN_LIMIT") "CONTEXT_SUMMARY_OUTPUT_LIMIT" else code
