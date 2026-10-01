package com.helix.app.engine

import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetTracker
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnRuntimeRecordEntity

/** Durable monotonic accounting/checkpoint bridge used by the live and rehydrated AgentLoop. */
internal class TurnRuntimeAccounting(
    private val storage: HelixStorage,
) : com.helix.core.agent.AgentLoopAccounting {
    override suspend fun restore(
        turnId: String,
        providerId: String,
        control: RunControlConfig,
    ): com.helix.core.agent.LoopUsageSnapshot? {
        val record = storage.turnRuntimeRecords.find(turnId) ?: return null
        validate(record, providerId, control)
        return com.helix.core.agent.LoopUsageSnapshot(
            record.modelId,
            record.consumedModelCalls,
            record.consumedTokens,
            record.admittedToolRounds,
        )
    }

    private fun validate(
        record: TurnRuntimeRecordEntity,
        providerId: String,
        control: RunControlConfig,
    ) {
        require(record.providerId == providerId) { "Turn runtime provider drift" }
        require(record.mode == control.mode.name) { "Turn runtime mode drift" }
        require(record.chatToolsEnabled == control.chatToolsEnabled) { "Turn runtime tool exposure drift" }
        require(record.budgetsJson == control.budgets.toStorageString()) { "Turn runtime budget drift" }
        require(record.reasoning == control.reasoning.name) { "Turn runtime reasoning drift" }
        require(record.goalBudgetsJson == control.goalBudgets.toStorageString()) { "Turn runtime Goal budget drift" }
    }

    override suspend fun checkpointModelAdmission(
        turnId: String,
        tracker: TurnBudgetTracker,
    ) {
        val row = storage.turnRuntimeRecords.find(turnId) ?: return
        if (row.consumedModelCalls == tracker.consumedCalls) return
        require(row.consumedModelCalls + 1 == tracker.consumedCalls) { "Turn model-call checkpoint jumped" }
        storage.turnRuntimeRecords.checkpointModelAdmission(turnId, row.consumedModelCalls)
    }

    override suspend fun checkpointTokens(
        turnId: String,
        tracker: TurnBudgetTracker,
    ) {
        val row = storage.turnRuntimeRecords.find(turnId) ?: return
        if (row.consumedTokens == tracker.consumedTokens) return
        storage.turnRuntimeRecords.checkpointTokens(turnId, row.consumedTokens, tracker.consumedTokens)
    }

    override suspend fun checkpointToolRound(
        turnId: String,
        admittedToolRounds: Int,
    ) {
        val row = storage.turnRuntimeRecords.find(turnId) ?: return
        require(row.admittedToolRounds == admittedToolRounds) { "Turn tool-round checkpoint drift" }
        storage.turnRuntimeRecords.checkpointToolRound(turnId, admittedToolRounds)
    }
}
