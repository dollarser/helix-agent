package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.MessageEntity

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sequence ASC")
    fun listBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM messages WHERE sessionId = :sessionId")
    fun maxSequence(sessionId: String): Long

    @Query("SELECT contentRef FROM messages WHERE sessionId = :sessionId AND contentRef IS NOT NULL")
    fun contentRefsBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE contentRef = :contentRef")
    fun countByContentRef(contentRef: String): Int
}
