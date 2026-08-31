package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * architecture doc 9.1: `goals` — objective, criteria (canonical JSON list), budgets
 * (`GoalBudgets` storage string), state, planId/planHash, checkpoint, plus the goal-level
 * cumulative counters persisted for recovery (ADR-0004). The field list is normative and
 * exceeds the detekt constructor threshold, hence the constructor suppression.
 */
@Suppress("LongParameterList")
@Entity(tableName = "goals", indices = [Index("state")])
data class GoalEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val criteria: String,
    val budgets: String,
    val state: String,
    val planId: String?,
    val planHash: String?,
    val nextCheckpoint: Long?,
    val correlationId: String,
    val runCount: Int,
    val modelCalls: Int,
    val toolCalls: Int,
    val totalTokens: Long,
    val runTimeMillis: Long,
    val currentWakeMillis: Long,
    val retries: Int,
    val lastWakeReason: String?,
    val error: String?,
    val finishReason: String?,
)

/** architecture doc 9.1: `goal_runs` — per-run wakeReason and outcome. */
@Entity(
    tableName = "goal_runs",
    foreignKeys =
        [
            ForeignKey(
                entity = GoalEntity::class,
                parentColumns = ["id"],
                childColumns = ["goalId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("goalId")],
)
data class GoalRunEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val wakeReason: String,
    val outcome: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val wakeDurationMillis: Long?,
    val modelCalls: Int,
    val toolCalls: Int,
    val tokens: Long,
)
