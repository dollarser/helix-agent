package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity

@Dao
interface MessageReferenceSnapshotDao {
    @Insert
    fun insert(entity: MessageReferenceSnapshotEntity): Long

    @Query("SELECT * FROM message_reference_snapshots WHERE messageId = :messageId ORDER BY ordinal")
    fun byMessage(messageId: String): List<MessageReferenceSnapshotEntity>

    @Query(
        "SELECT r.contentRef FROM message_reference_snapshots r " +
            "JOIN messages m ON m.id = r.messageId WHERE m.sessionId = :sessionId",
    )
    fun contentRefsByTargetSession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM message_reference_snapshots WHERE contentRef = :contentRef")
    fun countByContentRef(contentRef: String): Int
}
