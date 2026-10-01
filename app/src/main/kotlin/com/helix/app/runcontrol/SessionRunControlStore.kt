package com.helix.app.runcontrol

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionRunControlRecord

/**
 * Durable per-session run-control seam. [RunControlStore] remains the new-session defaults owner;
 * this store snapshots those defaults and never inherits later edits dynamically.
 */
class SessionRunControlStore(
    private val storage: HelixStorage,
    private val defaults: RunControlStore,
) {
    fun defaultSnapshot(): RunControlConfig = validated(defaults.current)

    fun forSession(sessionId: String): RunControlConfig? = storage.sessionRunControls.forSession(sessionId)?.toConfig()

    fun ensure(
        sessionId: String,
        nowEpochMillis: Long,
    ): RunControlConfig = forSession(sessionId) ?: defaultSnapshot().also { set(sessionId, it, nowEpochMillis) }

    fun set(
        sessionId: String,
        config: RunControlConfig,
        nowEpochMillis: Long,
    ): Long =
        storage.sessionRunControls.setForSession(
            sessionId,
            validated(config).toRecord(),
            nowEpochMillis,
        )

    fun copy(
        sourceSessionId: String,
        targetSessionId: String,
        nowEpochMillis: Long,
    ): RunControlConfig {
        val source = forSession(sourceSessionId) ?: defaultSnapshot()
        set(targetSessionId, source, nowEpochMillis)
        return source
    }

    private fun validated(config: RunControlConfig): RunControlConfig =
        config.copy(
            budgets = TurnBudgetBounds.validate(config.budgets),
            goalBudgets = GoalBudgetDefaults.validate(config.goalBudgets),
        )

    private fun RunControlConfig.toRecord(): SessionRunControlRecord =
        SessionRunControlRecord(
            mode = mode,
            chatToolsEnabled = chatToolsEnabled,
            budgets = budgets,
            reasoning = reasoning,
            goalBudgets = goalBudgets,
        )

    private fun SessionRunControlRecord.toConfig(): RunControlConfig =
        validated(
            RunControlConfig(
                mode = mode,
                chatToolsEnabled = chatToolsEnabled,
                budgets = budgets,
                reasoning = reasoning,
                goalBudgets = goalBudgets,
            ),
        )
}
