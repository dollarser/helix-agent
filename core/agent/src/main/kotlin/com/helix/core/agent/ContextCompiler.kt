package com.helix.core.agent

import com.helix.provider.api.ProviderContextSettings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure compaction selection over a bounded snapshot. It performs no reads, writes, model calls or
 * authorization decisions. The host materializes source data and atomically publishes a summary.
 */
class ContextCompiler(
    private val summaries: ContextSummaryFormat,
) {
    @Suppress("ReturnCount") // An unchanged request, irreducible segment and mismatched history are distinct.
    fun plan(
        snapshot: ContextSourceSnapshot,
        request: TurnContextRequest,
        control: RunControlConfig,
        settings: ProviderContextSettings,
        force: Boolean,
        inputScale: Double = 1.0,
    ): ContextCompactionPlan? {
        require(inputScale.isFinite() && inputScale >= 1.0)
        if (!needsPlan(request, control, settings, force, snapshot.inputFloor)) return null
        val previous = snapshot.checkpoint
        val history = snapshot.rows
        val selected = mutableListOf<ContextSourceRow>()
        val prefix = StringBuilder()
        previous?.let { prefix.append(summaries.summaryMessage(it).text).append('\n') }
        val summaryOutput =
            SummaryOutputBudget.forRequest(
                request.inputTokens(),
                control.budgets.maxOutputTokens,
                settings.window,
            )
        val inputLimit = compactionInputLimit(control, settings, summaryOutput, inputScale)
        var through: Long? = null
        var prefixBytes = TokenEstimator.utf8Bytes(prefix.toString())
        for (turn in ContextSegments.candidates(history, snapshot.currentTurnId)) {
            val serialized = turn.joinToString("\n", transform = ::serializeRow)
            val groupBytes = TokenEstimator.utf8Bytes(serialized) + 1
            val byteLimit =
                minOf(
                    inputLimit * TokenEstimator.CONSERVATIVE_BYTES_PER_TOKEN,
                    ContextSourceSnapshot.MAX_BODY_BYTES.toLong(),
                )
            if (groupBytes > byteLimit - prefixBytes) {
                if (selected.isEmpty()) throw ContextCapacityException("CONTEXT_SEGMENT_LIMIT")
                break
            }
            prefixBytes += groupBytes
            prefix.append(serialized).append('\n')
            selected.addAll(turn)
            through = maxOf(previous?.coveredThrough ?: 0, turn.last().sequence)
        }
        return through?.let { boundary ->
            val removed = selected.map { it.id }.toSet()
            val retained = ContextSegments.remainingRequest(snapshot, removed, request) ?: return null
            val output =
                SummaryOutputBudget.forRequest(
                    (request.inputTokens() - retained.inputTokens()).coerceAtLeast(0),
                    control.budgets.maxOutputTokens,
                    settings.window,
                )
            ContextCompactionPlan(
                boundary,
                output.modelRequest(request.model, prefix.toString(), summaries),
                retained,
                history.filter { it.sequence <= boundary && it.id !in removed }.map { it.id }.toSet(),
                request.inputTokens(),
            )
        }
    }

    private fun serializeRow(row: ContextSourceRow): String =
        buildJsonObject {
            put("id", row.id)
            put("role", row.role)
            put("kind", row.kind)
            put("content", row.content.orEmpty())
            put("attachmentCount", row.attachmentCount)
        }.toString()

    private fun compactionInputLimit(
        control: RunControlConfig,
        settings: ProviderContextSettings,
        output: SummaryOutputBudget,
        inputScale: Double,
    ): Long {
        val reserve = minOf(COMPACTION_ENVELOPE_RESERVE, settings.window / 4)
        return (
            minOf(settings.window - output.allowance - reserve, control.budgets.maxInputTokens - reserve) /
                inputScale
        ).toLong()
    }

    companion object {
        private const val COMPACTION_ENVELOPE_RESERVE = 2048L

        fun needsPlan(
            request: TurnContextRequest,
            control: RunControlConfig,
            settings: ProviderContextSettings,
            force: Boolean,
            inputFloor: Long,
        ): Boolean =
            force ||
                ContextCapacity.shouldCompact(
                    request,
                    settings,
                    control.budgets.maxInputTokens,
                    maxOf(request.inputTokens(), inputFloor),
                )
    }
}
