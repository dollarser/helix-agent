package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Rebuildable text projection; never an input to model history, authorization or recovery. */
@Entity(
    tableName = "message_search",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageSearchEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val contentRef: String,
    val body: String,
    val folded: String,
)
