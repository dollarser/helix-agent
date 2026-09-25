package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Durable per-Turn execution snapshot/checkpoint owned by TurnEngine.
 *
 * Configuration columns are immutable after insert. Runtime counters are monotonic live
 * checkpoints used for budget enforcement and audit. They are not crash-resume control state.
 *
 * Provider identity is intentionally not a foreign key: deleting/reconfiguring a provider must not
 * erase or rewrite the historical target a Turn was admitted against.
 */
@Entity(
    tableName = "turn_runtime_records",
    foreignKeys =
        [
            ForeignKey(
                entity = TurnEntity::class,
                parentColumns = ["id"],
                childColumns = ["turnId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class TurnRuntimeRecordEntity(
    @PrimaryKey val turnId: String,
    val version: Int,
    val providerId: String,
    val modelId: String,
    val providerSnapshot: String,
    val mode: String,
    val chatToolsEnabled: Boolean,
    val budgetsJson: String,
    val reasoning: String,
    val goalBudgetsJson: String,
    val consumedModelCalls: Int,
    val consumedTokens: Long,
    val admittedToolRounds: Int,
)
