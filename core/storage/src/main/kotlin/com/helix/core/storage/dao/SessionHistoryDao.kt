package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Query
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity
import com.helix.core.storage.entity.SessionEntity

/** Queries for the caller's effective conversation, never a global transcript inventory. */
@Dao
interface SessionHistoryDao {
    @Query(
        "SELECT s.* FROM sessions s JOIN turns t ON t.sessionId = s.id " +
            "JOIN tool_calls c ON c.turnId = t.id WHERE s.id = :sessionId AND t.id = :turnId " +
            "AND c.id = :callId AND c.name = :toolName AND c.state = 'RUNNING' " +
            "AND t.state = 'RUNNING_TOOL'",
    )
    fun caller(
        sessionId: String,
        turnId: String,
        callId: String,
        toolName: String,
    ): SessionEntity?

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND supersededBy IS NULL " +
            "AND kind = 'TEXT' AND role IN ('USER','ASSISTANT') AND sequence < :beforeSequence " +
            "ORDER BY sequence DESC LIMIT :limit",
    )
    fun page(
        sessionId: String,
        beforeSequence: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND id = :messageId " +
            "AND supersededBy IS NULL AND kind = 'TEXT' AND role IN ('USER','ASSISTANT')",
    )
    fun message(
        sessionId: String,
        messageId: String,
    ): MessageEntity?

    @Query("SELECT * FROM message_reference_snapshots WHERE messageId = :messageId ORDER BY ordinal")
    fun references(messageId: String): List<MessageReferenceSnapshotEntity>

    /** The CAS changes only the title, preserving workspace/configuration and concurrent user edits. */
    @Query(
        "UPDATE sessions SET title = :title, searchTitle = :searchTitle " +
            "WHERE id = :sessionId AND title = :expectedTitle " +
            "AND EXISTS (SELECT 1 FROM turns t JOIN tool_calls c ON c.turnId = t.id " +
            "WHERE t.sessionId = :sessionId AND t.id = :turnId AND t.state = 'RUNNING_TOOL' " +
            "AND c.id = :callId AND c.name = 'session.rename' AND c.state = 'RUNNING')",
    )
    fun rename(
        sessionId: String,
        turnId: String,
        callId: String,
        expectedTitle: String,
        title: String,
        searchTitle: String,
    ): Int
}
