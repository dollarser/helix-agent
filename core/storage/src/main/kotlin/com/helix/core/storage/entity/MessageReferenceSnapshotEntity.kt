package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable read manifest attached to an explicitly shared USER input. The owned binding and hash
 * authorize only the selected fixed revision. Source deletion leaves metadata, not a readable transcript.
 */
@Entity(
    tableName = "message_reference_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId", "ordinal"], unique = true), Index("contentRef")],
)
data class MessageReferenceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val messageId: String,
    val ordinal: Int,
    val sourceSessionId: String,
    val sourceSessionTitle: String,
    val selectionKind: String,
    val sourceMessageIdsJson: String,
    val contentRef: String,
    val contentSha256: String,
    val createdAtEpoch: Long,
)
