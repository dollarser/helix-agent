package com.helix.core.agent

import com.helix.core.model.ModelRequest

object ContextCommands {
    const val COMPACT = "/compact"
}

data class PreparedContext(
    val request: ModelRequest?,
    val plan: ContextCompactionPlan?,
    val failure: ModelStreamTerminal?,
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
        control: RunControlConfig,
    ): ContextCompactionCycle
}
