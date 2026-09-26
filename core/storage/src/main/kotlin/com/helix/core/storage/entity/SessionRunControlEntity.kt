package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Durable per-session agent-run configuration. A session snapshots app defaults when it is created;
 * later default edits never change this row. Turns still snapshot this configuration again at
 * admission, so edits affect only future Turns.
 */
@Entity(
    tableName = "session_run_controls",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionRunControlEntity(
    @PrimaryKey val sessionId: String,
    val mode: String,
    val chatToolsEnabled: Boolean,
    val turnBudgetsJson: String,
    val reasoning: String,
    val goalBudgetsJson: String,
    val configVersion: Int,
    val revision: Long,
    val createdAtEpoch: Long,
    val updatedAtEpoch: Long,
)
