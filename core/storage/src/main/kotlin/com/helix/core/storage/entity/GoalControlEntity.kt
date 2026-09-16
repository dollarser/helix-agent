package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Session ownership and versioned, deferred metadata edits. Activation is never persisted here. */
@Entity(
    tableName = "goal_controls",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class GoalControlEntity(
    @PrimaryKey val goalId: String,
    val sessionId: String,
    val revision: Long,
    val pendingTurnId: String?,
    val pendingJson: String?,
)
