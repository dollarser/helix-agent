package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** A user action, never model-generated text, controls creation from this source. */
@Entity(
    tableName = "session_creation_grants",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionCreationGrantEntity(
    @PrimaryKey val sourceSessionId: String,
    val allowed: Boolean,
    val revision: Long,
)

/** Kept after target deletion: a retry must not recreate a deleted conversation. */
@Entity(
    tableName = "session_creation_receipts",
    primaryKeys = ["sourceSessionId", "requestKey"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionCreationReceiptEntity(
    val sourceSessionId: String,
    val requestKey: String,
    val title: String,
    val createdSessionId: String,
    val sourceTurnId: String,
    val sourceCallId: String,
    val grantRevision: Long,
    val createdAt: Long,
)
