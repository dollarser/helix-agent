package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** One unsent composer snapshot per persisted session; never part of model history or audit. */
@Entity(
    tableName = "composer_drafts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ComposerDraftEntity(
    @PrimaryKey val sessionId: String,
    val revision: Long,
    val clientRequestId: String,
    val text: String,
    val attachmentIdsJson: String,
)
