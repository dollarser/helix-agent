package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable wake association. One run may contain multiple Turns; one Turn belongs to only one run. */
@Entity(
    tableName = "goal_turn_bindings",
    foreignKeys = [
        ForeignKey(
            entity = TurnEntity::class,
            parentColumns = ["id"],
            childColumns = ["turnId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GoalRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class GoalTurnBindingEntity(
    @PrimaryKey val turnId: String,
    val runId: String,
)
