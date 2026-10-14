package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Host-owned immutable source binding. A reference never grants access to its source. */
@Entity(
    tableName = "document_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("contentRef")],
)
data class DocumentSnapshotEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sourcePath: String,
    val format: String,
    val contentRef: String,
)
