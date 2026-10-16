package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.MessageSearchEntity

data class SessionSearchRow(
    val id: String,
    val title: String,
    val archivedAt: Long?,
    val createdAt: Long,
    val lastActivityAt: Long,
    val titleMatch: Boolean,
    val messageSnippet: String?,
)

data class MessageSearchLocation(
    val id: String,
    val sequence: Long,
)

@Dao
interface SessionSearchDao {
    @Query(
        "SELECT m.id, m.sequence FROM message_search x JOIN messages m ON m.id = x.messageId " +
            "WHERE m.sessionId = :sessionId AND x.sessionId = :sessionId AND m.supersededBy IS NULL " +
            "AND m.contentRef = x.contentRef AND instr(x.folded, :query) > 0 " +
            "AND (:before IS NULL OR m.sequence < :before) AND (:after IS NULL OR m.sequence > :after) " +
            "ORDER BY CASE WHEN :forward THEN m.sequence END ASC, m.sequence DESC LIMIT 1",
    )
    fun locateMessage(
        sessionId: String,
        query: String,
        before: Long?,
        after: Long?,
        forward: Boolean,
    ): MessageSearchLocation?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: MessageSearchEntity)

    @Query(
        "INSERT INTO message_search (messageId, sessionId, contentRef, body, folded) " +
            "SELECT :targetId, :sessionId, contentRef, body, folded FROM message_search WHERE messageId = :sourceId",
    )
    fun copy(
        sourceId: String,
        targetId: String,
        sessionId: String,
    )

    @Query("UPDATE sessions SET lastActivityAt = MAX(lastActivityAt, :timestamp) WHERE id = :sessionId")
    fun touch(
        sessionId: String,
        timestamp: Long,
    )

    // All effective visible text participates. Limit bounds returned hits, never the searched history.
    // SQLite tokenizers do not preserve literal Chinese/sub-word matching; use a persisted projection
    // with per-session lookup instead of opening/truncating hundreds of content files on each search.
    @Query(
        "WITH hits AS (SELECT s.id, s.title, s.archivedAt, s.createdAt, s.lastActivityAt, " +
            "instr(s.searchTitle, :query) > 0 AS titleMatch, " +
            "(SELECT substr(x.body, MAX(1, instr(x.folded, :query) - 60), MIN(length(:query), 120) + 120) " +
            "FROM message_search x JOIN messages m ON m.id = x.messageId " +
            "WHERE x.sessionId = s.id AND m.sessionId = s.id AND m.supersededBy IS NULL " +
            "AND m.contentRef = x.contentRef AND instr(x.folded, :query) > 0 " +
            "ORDER BY m.sequence DESC LIMIT 1) AS messageSnippet FROM sessions s) " +
            "SELECT * FROM hits WHERE titleMatch OR messageSnippet IS NOT NULL " +
            "ORDER BY lastActivityAt DESC, id ASC LIMIT :limit",
    )
    fun search(
        query: String,
        limit: Int,
    ): List<SessionSearchRow>
}
