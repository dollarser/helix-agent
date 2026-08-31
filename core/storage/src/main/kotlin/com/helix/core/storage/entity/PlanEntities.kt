package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * architecture doc 9.1: `plans` — objective, version, hash, state, evidenceRef. Per ADR-0001,
 * `PlanArtifact` is a writer-only type (no decoder), so the normalized columns are the
 * recovery source and `hash` (SHA-256 over the canonical storage string) binds the columns to
 * the exact artifact version.
 */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val assumptionsJson: String,
    val acceptanceCriteriaJson: String,
    val risksJson: String,
    val version: Int,
    val hash: String,
    val state: String,
    val evidenceRef: String?,
)

/** architecture doc 9.1: `plan_steps` — ordered, unique per (plan, sequence). */
@Entity(
    tableName = "plan_steps",
    foreignKeys =
        [
            ForeignKey(
                entity = PlanEntity::class,
                parentColumns = ["id"],
                childColumns = ["planId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["planId", "sequence"], unique = true)],
)
data class PlanStepEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long,
    val planId: String,
    val sequence: Int,
    val title: String,
    val description: String,
)
