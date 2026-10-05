package com.helix.core.storage.repository

import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.dao.SessionRunControlDao
import com.helix.core.storage.entity.SessionRunControlEntity

data class SessionRunControlRecord(
    val mode: AgentMode,
    val chatToolsEnabled: Boolean,
    val budgets: TurnBudgets,
    val reasoning: ReasoningEffort,
    val goalBudgets: GoalBudgets,
    val configVersion: Int = CURRENT_CONFIG_VERSION,
    val immediateMessages: Boolean = false,
) {
    companion object {
        const val CURRENT_CONFIG_VERSION = 1
    }
}

/** One fixed run-control snapshot per Session; writes advance a monotonic revision. */
class SessionRunControlRepository(
    private val dao: SessionRunControlDao,
) {
    fun forSession(sessionId: String): SessionRunControlRecord? = dao.bySession(sessionId)?.toRecord()

    fun setForSession(
        sessionId: String,
        record: SessionRunControlRecord,
        nowEpochMillis: Long,
    ): Long {
        require(record.configVersion == SessionRunControlRecord.CURRENT_CONFIG_VERSION)
        val existing = dao.bySession(sessionId)
        val revision = (existing?.revision ?: 0L) + 1L
        dao.insert(
            SessionRunControlEntity(
                sessionId = sessionId,
                mode = record.mode.name,
                chatToolsEnabled = record.chatToolsEnabled,
                turnBudgetsJson = record.budgets.toStorageString(),
                reasoning = record.reasoning.name,
                goalBudgetsJson = record.goalBudgets.toStorageString(),
                configVersion = record.configVersion,
                revision = revision,
                createdAtEpoch = existing?.createdAtEpoch ?: nowEpochMillis,
                updatedAtEpoch = nowEpochMillis,
                immediateMessages = record.immediateMessages,
            ),
        )
        return revision
    }

    fun copy(
        sourceSessionId: String,
        targetSessionId: String,
        nowEpochMillis: Long,
    ): Long? {
        val source = forSession(sourceSessionId) ?: return null
        return setForSession(targetSessionId, source, nowEpochMillis)
    }

    private fun SessionRunControlEntity.toRecord(): SessionRunControlRecord {
        require(configVersion == SessionRunControlRecord.CURRENT_CONFIG_VERSION)
        return SessionRunControlRecord(
            mode = AgentMode.valueOf(mode),
            chatToolsEnabled = chatToolsEnabled,
            budgets = TurnBudgets.parse(turnBudgetsJson),
            reasoning = ReasoningEffort.valueOf(reasoning),
            goalBudgets = GoalBudgets.parse(goalBudgetsJson),
            configVersion = configVersion,
            immediateMessages = immediateMessages,
        )
    }
}
