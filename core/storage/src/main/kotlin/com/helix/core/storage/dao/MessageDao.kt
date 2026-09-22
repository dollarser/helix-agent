package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.MessageEntity

@Dao
@Suppress("TooManyFunctions") // One table exposes both effective history and retained revision evidence.
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND supersededBy IS NULL ORDER BY sequence ASC")
    fun listBySession(sessionId: String): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND supersededBy IS NULL " +
            "AND sequence > :after ORDER BY sequence ASC LIMIT :limit",
    )
    fun pageAfter(
        sessionId: String,
        after: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND (:includeSuperseded OR supersededBy IS NULL) " +
            "AND kind = :kind ORDER BY sequence DESC LIMIT 1",
    )
    fun latestOfKind(
        sessionId: String,
        kind: String,
        includeSuperseded: Boolean = false,
    ): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER' " +
            "AND supersededBy IS NULL ORDER BY sequence DESC LIMIT 1",
    )
    fun latestUser(sessionId: String): MessageEntity?

    @Query(
        "UPDATE messages SET supersededBy = :requestId WHERE sessionId = :sessionId " +
            "AND sequence >= :from AND supersededBy IS NULL",
    )
    fun supersedeFrom(
        sessionId: String,
        from: Long,
        requestId: String,
    )

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sequence ASC")
    fun allRevisions(sessionId: String): List<MessageEntity>

    @Query(
        "SELECT DISTINCT turnId FROM messages WHERE sessionId = :sessionId " +
            "AND supersededBy IS NOT NULL AND turnId IS NOT NULL",
    )
    fun supersededTurns(sessionId: String): List<String>

    @Query("SELECT supersededBy FROM messages WHERE turnId = :turnId AND supersededBy IS NOT NULL LIMIT 1")
    fun supersededRequest(turnId: String): String?

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM messages WHERE sessionId = :sessionId")
    fun maxSequence(sessionId: String): Long

    @Query("SELECT contentRef FROM messages WHERE sessionId = :sessionId AND contentRef IS NOT NULL")
    fun contentRefsBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE contentRef = :contentRef")
    fun countByContentRef(contentRef: String): Int

    /**
     * Read-only search candidates (HXA-191): the [limit] most recent stored bodies,
     * newest session first and newest message within a session. Consumed by the
     * bounded message-text search; never writes.
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "INNER JOIN sessions s ON s.id = m.sessionId " +
            "WHERE m.contentRef IS NOT NULL AND m.supersededBy IS NULL " +
            "ORDER BY s.createdAt DESC, m.sequence DESC " +
            "LIMIT :limit",
    )
    fun contentSearchCandidates(limit: Int): List<MessageEntity>
}
