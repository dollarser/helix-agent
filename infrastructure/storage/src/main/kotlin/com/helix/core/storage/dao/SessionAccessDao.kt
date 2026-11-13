package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.entity.SessionAccessGrantEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Metadata-only projection. No credentials, directory, provider or transcript fields. */
data class SessionAccessSummary(
    val targetSessionId: String,
    val title: String,
    val archivedAt: Long?,
    val readHistory: Boolean,
    val manageMetadata: Boolean,
    val revision: Long,
    val delegateTasks: Boolean = false,
)

/** Execution identity/state only; never a transcript or execution configuration. */
data class SessionAccessExecution(
    val turnId: String,
    val state: String,
)

@Dao
interface SessionAccessDao : UserSessionMetadataCommands {
    @Query(
        "SELECT id AS turnId, state FROM turns WHERE sessionId = :target AND state NOT IN (:terminalStates) " +
            "ORDER BY startedAt DESC, rowid DESC LIMIT 1",
    )
    fun execution(
        target: String,
        terminalStates: List<String>,
    ): SessionAccessExecution?

    @Query("SELECT * FROM session_access_grants WHERE sourceSessionId = :source AND targetSessionId = :target")
    fun grant(
        source: String,
        target: String,
    ): SessionAccessGrantEntity?

    @Query("SELECT * FROM session_access_grants WHERE sourceSessionId = :source ORDER BY targetSessionId")
    fun grants(source: String): List<SessionAccessGrantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(grant: SessionAccessGrantEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun appendGrantAudit(event: AuditEventEntity)

    @Transaction
    fun setGrant(
        source: String,
        target: String,
        read: Boolean,
        manage: Boolean,
        delegateTasks: Boolean = false,
    ): SessionAccessGrantEntity {
        require(source != target)
        val row =
            SessionAccessGrantEntity(
                source,
                target,
                read,
                manage,
                Math.addExact(grant(source, target)?.revision ?: 0L, 1L),
                delegateTasks,
            )
        put(row)
        appendGrantAudit(
            AuditEventEntity(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                correlationId = source,
                type = "session.access_grant",
                actor = "user",
                redactedPayload =
                    buildJsonObject {
                        put("sourceSessionId", source)
                        put("targetSessionId", target)
                        put("readHistory", read)
                        put("manageMetadata", manage)
                        put("delegateTasks", delegateTasks)
                        put("revision", row.revision)
                    }.toString(),
                timestamp = System.currentTimeMillis(),
            ),
        )
        return row
    }

    @Query(
        "SELECT g.targetSessionId, s.title, s.archivedAt, g.readHistory, " +
            "g.manageMetadata, g.revision, g.delegateTasks " +
            "FROM session_access_grants g JOIN sessions s ON s.id = g.targetSessionId " +
            "WHERE g.sourceSessionId = :source " +
            "AND (g.readHistory = 1 OR g.manageMetadata = 1 OR g.delegateTasks = 1) " +
            "AND (:afterId IS NULL OR g.targetSessionId > :afterId) ORDER BY g.targetSessionId LIMIT :limit",
    )
    fun list(
        source: String,
        afterId: String?,
        limit: Int,
    ): List<SessionAccessSummary>

    @Query(
        "UPDATE sessions SET title = :title, searchTitle = :searchTitle " +
            "WHERE id = :target AND title = :expectedTitle " +
            "AND (:source = :target OR EXISTS (SELECT 1 FROM session_access_grants g " +
            "WHERE g.sourceSessionId = :source " +
            "AND g.targetSessionId = :target AND g.manageMetadata = 1 AND g.revision = :revision)) " +
            "AND EXISTS (SELECT 1 FROM turns t JOIN tool_calls c ON c.turnId = t.id " +
            "WHERE t.sessionId = :source AND t.id = :turnId AND t.state = 'RUNNING_TOOL' " +
            "AND c.id = :callId AND c.name = 'session.rename' AND c.state = 'RUNNING')",
    )
    @Suppress("LongParameterList") // Room binds identity, grant revision and title CAS in one atomic statement.
    fun rename(
        source: String,
        target: String,
        revision: Long,
        turnId: String,
        callId: String,
        expectedTitle: String,
        title: String,
        searchTitle: String,
    ): Int

    @Query(
        "UPDATE sessions SET archivedAt = :archivedAt WHERE id = :target " +
            "AND (archivedAt IS NOT NULL) = :expectedArchived " +
            "AND (:source = :target OR EXISTS (SELECT 1 FROM session_access_grants g " +
            "WHERE g.sourceSessionId = :source " +
            "AND g.targetSessionId = :target AND g.manageMetadata = 1 AND g.revision = :revision)) " +
            "AND EXISTS (SELECT 1 FROM turns t JOIN tool_calls c ON c.turnId = t.id " +
            "WHERE t.sessionId = :source AND t.id = :turnId AND t.state = 'RUNNING_TOOL' " +
            "AND c.id = :callId AND c.name = :toolName AND c.state = 'RUNNING')",
    )
    @Suppress("LongParameterList") // Room binds identity, grant revision and archive CAS in one atomic statement.
    fun archive(
        source: String,
        target: String,
        revision: Long,
        turnId: String,
        callId: String,
        toolName: String,
        expectedArchived: Boolean,
        archivedAt: Long?,
    ): Int
}
