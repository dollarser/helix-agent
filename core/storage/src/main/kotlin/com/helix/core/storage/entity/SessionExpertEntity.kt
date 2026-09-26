package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** One durable user-authored behavior profile bound to a Session. */
@Entity(
    tableName = "session_experts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionExpertEntity(
    @PrimaryKey val sessionId: String,
    val profileId: String,
    val displayName: String,
    val instruction: String,
    val recommendedSkillIdsJson: String,
    val recommendedConnectorIdsJson: String,
    val recommendedMode: String?,
    val revision: Long,
    val createdAtEpoch: Long,
    val updatedAtEpoch: Long,
)
