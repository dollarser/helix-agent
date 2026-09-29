package com.helix.app.chat

import com.helix.app.agent.UnresolvedEffectPolicy
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Bounded model-visible facts for a successor Turn; never another persisted conversation row. */
internal object RecoverySummaryBuilder {
    private const val MAX_CHARS = 12_000
    private const val MAX_CALLS = 32
    private const val MAX_SUMMARY_CHARS = 512

    data class ToolFact(
        val name: String,
        val state: String,
        val resultStatus: String? = null,
        val resultSummary: String? = null,
        val reviewDecision: String? = null,
    )

    fun build(
        storage: HelixStorage,
        predecessorTurnId: String,
    ): String {
        val turn = storage.turns.resolve(predecessorTurnId)
        val state = TurnState.valueOf(turn.state)
        require(state in setOf(TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW)) {
            "recovery predecessor is not interrupted/review-pending: $state"
        }
        val reviews = storage.toolCallReviews.listByTurn(predecessorTurnId).associateBy { it.toolCallId }
        val calls =
            storage.toolCalls.listByTurn(predecessorTurnId).takeLast(MAX_CALLS).map { call ->
                val result = storage.toolResults.byToolCall(call.id)
                ToolFact(
                    name = call.name,
                    state = call.state,
                    resultStatus = result?.status,
                    resultSummary = result?.summary,
                    reviewDecision = reviews[call.id]?.decision,
                )
            }
        return format(predecessorTurnId, state, turn.errorCode, calls)
    }

    fun unresolvedEffectWarning(
        storage: HelixStorage,
        sessionId: String,
        excludeTurnId: String?,
    ): String? {
        val turnIds = UnresolvedEffectPolicy.unresolvedTurnIds(storage, sessionId).filterNot { it == excludeTurnId }
        if (turnIds.isEmpty()) return null
        val lines = mutableListOf<String>()
        for (turnId in turnIds.asReversed()) {
            val unresolved = storage.toolCallReviews.unresolvedToolCallIds(turnId)
            for (toolCallId in unresolved) {
                val call = storage.toolCalls.resolve(toolCallId)
                lines += "- turn=$turnId tool=${call.name} state=${call.state}"
                if (lines.size >= MAX_CALLS) break
            }
            if (lines.size >= MAX_CALLS) break
        }
        return buildString {
            appendLine("[UnresolvedEffects]")
            appendLine("Earlier external effects still require review before side-effecting tools can run:")
            lines.forEach(::appendLine)
            append(
                "Read-only inspection is allowed; do not retry these effects until " +
                    "review/state verification resolves them. " +
                    "If no reliable proof is available, report the uncertainty " +
                    "and end the attempt; do not ask the user to repair or acknowledge the unknown effect.",
            )
        }.take(MAX_CHARS)
    }

    fun format(
        predecessorTurnId: String,
        state: TurnState,
        errorCode: String?,
        calls: List<ToolFact>,
    ): String {
        require(state in setOf(TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW))
        val text =
            buildString {
                appendLine("[RecoverySummary]")
                appendLine("Previous Turn: $predecessorTurnId")
                appendLine("Previous state: ${state.name}")
                errorCode?.takeIf { it.isNotBlank() }?.let { appendLine("Previous error: ${bounded(it)}") }
                if (calls.isEmpty()) {
                    appendLine("Tool facts: none")
                } else {
                    appendLine("Tool facts (durable, in original order):")
                    calls.takeLast(MAX_CALLS).forEach { fact ->
                        append("- ")
                        append(fact.name)
                        append(" [")
                        append(fact.state)
                        append(']')
                        fact.resultStatus?.let { status ->
                            append(" result=")
                            append(status)
                            fact.resultSummary?.let { summary ->
                                append(": ")
                                append(bounded(summary))
                            }
                        }
                        fact.reviewDecision?.let { review ->
                            append(" review=")
                            append(review)
                        }
                        appendLine()
                    }
                }
                appendLine(
                    "Continue from the current durable world state. Inspect files/tests/external state as needed.",
                )
                append(
                    "Do not blindly replay unresolved or acknowledged-unknown external effects; " +
                        "any new effect must be a new ToolCall through normal policy/approval.",
                )
            }
        return text.take(MAX_CHARS)
    }

    private fun bounded(value: String): String = value.replace('\n', ' ').take(MAX_SUMMARY_CHARS)
}
