package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Explicit user-selected cross-session scope. Revoked rows retain their revision. */
@Entity(
    tableName = "session_access_grants",
    primaryKeys = ["sourceSessionId", "targetSessionId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("targetSessionId")],
)
data class SessionAccessGrantEntity(
    val sourceSessionId: String,
    val targetSessionId: String,
    val readHistory: Boolean,
    val manageMetadata: Boolean,
    val revision: Long,
    val delegateTasks: Boolean = false,
)
