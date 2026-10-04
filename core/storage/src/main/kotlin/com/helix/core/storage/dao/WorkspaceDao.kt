package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionWorkspaceEntity
import com.helix.core.storage.entity.WorkspaceEntity

@Suppress("TooManyFunctions") // One registry owns resource, session and immutable request binding facts.
@Dao
interface WorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(workspace: WorkspaceEntity)

    @Query("SELECT * FROM workspaces WHERE id = :id")
    fun find(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE identityKey = :identity")
    fun byIdentity(identity: String): WorkspaceEntity?

    @Query(
        "SELECT * FROM workspaces WHERE ownerSessionId = :sessionId " +
            "AND ownership = 'MANAGED' ORDER BY createdAt LIMIT 1",
    )
    fun ownedBy(sessionId: String): WorkspaceEntity?

    @Query("UPDATE workspaces SET ownerSessionId = NULL WHERE ownerSessionId = :sessionId AND ownership = 'MANAGED'")
    fun releaseOwnership(sessionId: String): Int

    @Query("SELECT * FROM workspaces ORDER BY createdAt, id")
    fun list(): List<WorkspaceEntity>

    @Query("UPDATE workspaces SET availability = :availability, witness = :witness WHERE id = :id")
    fun updateAvailability(
        id: String,
        availability: String,
        witness: String?,
    ): Int

    @Query("UPDATE workspaces SET availability = :next WHERE id = :id AND availability = :expected")
    fun compareAvailability(
        id: String,
        expected: String,
        next: String,
    ): Int

    @Query(
        "UPDATE workspaces SET ownerSessionId = NULL, availability = 'UNAVAILABLE', " +
            "identityKey = identityKey || ':retired:' || id WHERE id = :id",
    )
    fun retire(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun bind(binding: SessionWorkspaceEntity)

    @Query("SELECT * FROM session_workspaces WHERE sessionId = :sessionId")
    fun binding(sessionId: String): SessionWorkspaceEntity?

    @Query("SELECT COUNT(*) FROM session_workspaces WHERE workspaceId = :workspaceId")
    fun references(workspaceId: String): Int

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM sessions s JOIN workspaces w ON w.ownerSessionId = s.id " +
            "WHERE w.id = :workspaceId) AS ownerSessions, " +
            "(SELECT COUNT(*) FROM projects WHERE workspaceId = :workspaceId) AS projects, " +
            "(SELECT COUNT(*) FROM session_workspaces WHERE workspaceId = :workspaceId) AS sessionBindings, " +
            "(SELECT COUNT(*) FROM model_call_workspaces WHERE workspaceId = :workspaceId) AS modelRequests, " +
            "(SELECT COUNT(*) FROM artifacts WHERE " +
            "substr(relativePath, 1, length(:referencePrefix)) = :referencePrefix) AS artifacts, " +
            "(SELECT COUNT(*) FROM tool_calls WHERE instr(argsJson, :referencePrefix) > 0 " +
            "AND state NOT IN ('COMPLETED', 'FAILED', 'DENIED', 'CANCELLED')) AS pendingCalls",
    )
    fun retentionReferences(
        workspaceId: String,
        referencePrefix: String,
    ): com.helix.core.storage.entity.WorkspaceReferences

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun recordRequest(binding: com.helix.core.storage.entity.ModelCallWorkspaceEntity)

    @Query("SELECT * FROM model_call_workspaces WHERE modelCallId = :modelCallId")
    fun requestBinding(modelCallId: String): com.helix.core.storage.entity.ModelCallWorkspaceEntity?
}
