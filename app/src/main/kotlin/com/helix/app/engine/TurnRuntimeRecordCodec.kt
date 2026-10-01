package com.helix.app.engine

import com.helix.core.agent.GoalBudgetDefaults
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.entity.TurnRuntimeRecordEntity
import com.helix.core.storage.repository.ExpertProfile
import com.helix.core.storage.repository.TurnRuntimeRecordRepository

/** Typed, fail-closed codec between app execution policy and the durable TurnEngine runtime row. */
internal object TurnRuntimeRecordCodec {
    fun startRecord(
        turnId: String,
        providerId: String,
        modelId: String,
        providerSnapshot: String,
        control: RunControlConfig,
        expert: ExpertProfile? = null,
    ): TurnRuntimeRecordEntity =
        TurnRuntimeRecordEntity(
            turnId = turnId,
            version = TurnRuntimeRecordRepository.CURRENT_VERSION,
            providerId = providerId,
            modelId = modelId,
            providerSnapshot = providerSnapshot,
            mode = control.mode.name,
            chatToolsEnabled = control.chatToolsEnabled,
            budgetsJson = control.budgets.toStorageString(),
            reasoning = control.reasoning.name,
            goalBudgetsJson = control.goalBudgets.toStorageString(),
            expertProfileJson = expert?.let(ExpertProfileSnapshotCodec::encode),
            consumedModelCalls = 0,
            consumedTokens = 0,
            admittedToolRounds = 0,
        )

    fun decode(record: TurnRuntimeRecordEntity): TurnRuntimeSnapshot {
        require(record.version == TurnRuntimeRecordRepository.CURRENT_VERSION) {
            "unsupported turn runtime record version: ${record.version}"
        }
        val control =
            RunControlConfig(
                mode = AgentMode.valueOf(record.mode),
                chatToolsEnabled = record.chatToolsEnabled,
                budgets = TurnBudgetBounds.validate(TurnBudgets.parse(record.budgetsJson)),
                reasoning = ReasoningEffort.valueOf(record.reasoning),
                goalBudgets = GoalBudgetDefaults.validate(GoalBudgets.parse(record.goalBudgetsJson)),
            )
        require(record.providerId.isNotBlank()) { "providerId must not be blank" }
        require(record.modelId.isNotBlank()) { "modelId must not be blank" }
        require(record.providerSnapshot.isNotBlank()) { "providerSnapshot must not be blank" }
        require(record.consumedModelCalls in 0..control.budgets.maxModelCalls) {
            "consumed model calls exceed Turn budget"
        }
        require(record.consumedTokens in 0..control.budgets.maxTotalTokens) {
            "consumed tokens exceed Turn budget"
        }
        require(record.admittedToolRounds in 0..control.budgets.maxSteps) {
            "admitted tool rounds exceed Turn budget"
        }
        return TurnRuntimeSnapshot(
            turnId = record.turnId,
            providerId = record.providerId,
            modelId = record.modelId,
            providerSnapshot = record.providerSnapshot,
            control = control,
            expert = record.expertProfileJson?.let(ExpertProfileSnapshotCodec::decode),
            consumedModelCalls = record.consumedModelCalls,
            consumedTokens = record.consumedTokens,
            admittedToolRounds = record.admittedToolRounds,
        )
    }
}

internal data class TurnRuntimeSnapshot(
    val turnId: String,
    val providerId: String,
    val modelId: String,
    val providerSnapshot: String,
    val control: RunControlConfig,
    val expert: ExpertProfile?,
    val consumedModelCalls: Int,
    val consumedTokens: Long,
    val admittedToolRounds: Int,
)
