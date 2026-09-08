package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable admission facts; uncertain reservations are charged once on recovery, never replayed. */
@Entity(
    tableName = "goal_usage_reservations",
    foreignKeys = [
        ForeignKey(
            entity = GoalRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class GoalUsageReservationEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val kind: String,
    val reservedTokens: Long,
    val reservedMillis: Long,
    val state: String,
    val chargedTokens: Long?,
    val chargedMillis: Long?,
)
