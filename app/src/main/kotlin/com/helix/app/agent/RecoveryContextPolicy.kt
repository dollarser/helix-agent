package com.helix.app.agent

import com.helix.core.model.ModelRole

/** Model-history policy for successor Turns: old execution protocol is replaced by RecoverySummary facts. */
internal object RecoveryContextPolicy {
    fun modelHistoryRows(
        rows: List<ChatHistoryBuilder.PersistedRow>,
        predecessorTurnId: String?,
    ): List<ChatHistoryBuilder.PersistedRow> = rows.filter { row -> keep(row.turnId, row.role, predecessorTurnId) }

    fun keep(
        turnId: String?,
        role: String,
        predecessorTurnId: String?,
    ): Boolean =
        com.helix.core.agent.RecoveryContextFilter
            .keep(turnId, role, predecessorTurnId)
}
