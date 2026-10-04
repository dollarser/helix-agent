package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ProjectEntity
import com.helix.core.storage.entity.ProjectSessionEntity
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions") // Project lifecycle and membership share one transactional owner.
@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC, id")
    fun observe(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project_sessions")
    fun observeMemberships(): Flow<List<ProjectSessionEntity>>

    @Query("SELECT * FROM projects WHERE workspaceId = :workspaceId ORDER BY createdAt, id")
    fun forWorkspace(workspaceId: String): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun find(id: String): ProjectEntity?

    @Query("SELECT p.* FROM projects p JOIN project_sessions s ON p.id = s.projectId WHERE s.sessionId = :sessionId")
    fun forSession(sessionId: String): ProjectEntity?

    @Query(
        "SELECT p.* FROM projects p JOIN project_sessions s ON s.projectId = p.id " +
            "JOIN turns t ON t.sessionId = s.sessionId JOIN tool_calls c ON c.turnId = t.id " +
            "WHERE s.sessionId = :sessionId AND t.id = :turnId AND c.id = :toolCallId " +
            "AND c.state = 'RUNNING' AND t.state NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')",
    )
    fun forTool(
        sessionId: String,
        turnId: String,
        toolCallId: String,
    ): ProjectEntity?

    @Query("SELECT sessionId FROM project_sessions WHERE projectId = :projectId")
    fun sessionIds(projectId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(project: ProjectEntity)

    @Suppress("LongParameterList") // Room binds the explicit revision-checked update columns.
    @Query(
        "UPDATE projects SET name = :name, description = :description, instructions = :instructions, " +
            "workspaceId = :workspaceId, relativePath = :relativePath, updatedAt = :now, " +
            "revision = revision + 1 WHERE id = :id AND revision = :revision",
    )
    fun update(
        id: String,
        revision: Long,
        name: String,
        description: String,
        instructions: String,
        workspaceId: String,
        relativePath: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE projects SET archivedAt = :archivedAt, updatedAt = :now, revision = revision + 1 " +
            "WHERE id = :id AND revision = :revision",
    )
    fun archive(
        id: String,
        revision: Long,
        archivedAt: Long?,
        now: Long,
    ): Int

    @Query("DELETE FROM projects WHERE id = :id AND revision = :revision")
    fun delete(
        id: String,
        revision: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun bind(membership: ProjectSessionEntity)

    @Query("DELETE FROM project_sessions WHERE sessionId = :sessionId")
    fun unbind(sessionId: String)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM turns WHERE sessionId = :sessionId " +
            "AND state NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')) " +
            "OR EXISTS(SELECT 1 FROM session_inputs WHERE sessionId = :sessionId " +
            "AND state IN ('PENDING','NEEDS_ATTENTION')) " +
            "OR EXISTS(SELECT 1 FROM goal_controls c JOIN goals g ON g.id = c.goalId " +
            "WHERE c.sessionId = :sessionId AND g.state NOT IN ('COMPLETED','FAILED','CANCELLED')) " +
            "OR EXISTS(SELECT 1 FROM tool_calls c JOIN turns t ON t.id = c.turnId " +
            "WHERE t.sessionId = :sessionId AND c.state NOT IN ('COMPLETED','FAILED','CANCELLED','DENIED'))",
    )
    fun busy(sessionId: String): Boolean
}
