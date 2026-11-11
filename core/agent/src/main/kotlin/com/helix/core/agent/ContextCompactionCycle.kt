package com.helix.core.agent

import com.helix.core.model.ModelRequest

object ContextCommands {
    const val COMPACT = "/compact"

    data class CompactCommand(
        val focus: String?,
    )

    /** Recognize a whole command token, never a prefix such as /compactly. */
    fun parseCompact(text: String?): CompactCommand? {
        val command = text?.trim().orEmpty()
        if (!command.startsWith(COMPACT)) return null
        val remainder = command.substring(COMPACT.length)
        return if (remainder.isNotEmpty() && !remainder.first().isWhitespace()) {
            null
        } else {
            CompactCommand(remainder.trim().takeIf { it.isNotEmpty() })
        }
    }

    fun parseCompact(message: com.helix.core.model.ModelMessage?): CompactCommand? =
        if (message?.role == com.helix.core.model.ModelRole.USER) parseCompact(message.text) else null

    fun compact(focus: String?): String = focus?.trim()?.takeIf { it.isNotEmpty() }?.let { "$COMPACT $it" } ?: COMPACT
}

data class PreparedContext(
    val request: ModelRequest?,
    val plan: ContextCompactionPlan?,
    val failure: ModelStreamTerminal?,
    /** The exact projected context for diagnostics, manifests and prompt persistence. */
    val context: TurnContextRequest,
)

/** Per-Turn bounded summary cycle; only the journal can publish its resulting checkpoint. */
interface ContextCompactionCycle {
    fun observe(
        request: TurnContextRequest,
        actualInput: Long?,
    )

    suspend fun admissionInput(request: TurnContextRequest): Long

    suspend fun prepare(request: TurnContextRequest): PreparedContext

    suspend fun finish(
        plan: ContextCompactionPlan,
        stream: ModelStreamState,
        decision: ModelStreamTerminal,
        journal: AgentTurnJournal,
        nextId: String,
        notice: String,
        unchangedNotice: String,
    ): ModelStreamTerminal?
}

fun interface ContextCompactionCycles {
    suspend fun create(
        sessionId: String,
        turnId: String,
        providerId: String,
        context: TurnContextRequest,
    ): ContextCompactionCycle
}
