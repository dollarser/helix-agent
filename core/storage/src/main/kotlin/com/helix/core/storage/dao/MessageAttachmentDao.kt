package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.MessageAttachmentEntity

@Dao
interface MessageAttachmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(attachment: MessageAttachmentEntity)

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY ordinal ASC")
    fun listByMessage(messageId: String): List<MessageAttachmentEntity>

    @Query("DELETE FROM message_attachments WHERE messageId = :messageId")
    fun deleteByMessage(messageId: String)
}
