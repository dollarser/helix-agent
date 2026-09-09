package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionEntity

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun list(): List<SessionEntity>

    /** Affected row count is 0 when the session is missing or already archived. */
    @Query("UPDATE sessions SET archivedAt = :archivedAt WHERE id = :id AND archivedAt IS NULL")
    fun archive(
        id: String,
        archivedAt: Long,
    ): Int

    @Query("UPDATE sessions SET archivedAt = NULL WHERE id = :id AND archivedAt IS NOT NULL")
    fun restore(id: String): Int

    /**
     * Binds a provider to a session that has NONE (HXA-056 share-draft sessions are created
     * provider-free). Affected row count is 0 when the session is missing or ALREADY bound —
     * a session that ever carried a provider is never re-bound by this query.
     */
    @Query("UPDATE sessions SET providerId = :providerId, modelId = :modelId WHERE id = :id AND providerId IS NULL")
    fun bindProvider(
        id: String,
        providerId: String,
        modelId: String,
    ): Int

    @Query(
        "UPDATE sessions SET providerId = :providerId, modelId = :modelId " +
            "WHERE id = :id AND archivedAt IS NULL AND NOT EXISTS " +
            "(SELECT 1 FROM turns WHERE sessionId = :id AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED'))",
    )
    fun selectModel(
        id: String,
        providerId: String,
        modelId: String,
    ): Int

    @Query("UPDATE sessions SET title = :title, directoryRef = :directoryRef WHERE id = :id")
    fun updateDetails(
        id: String,
        title: String,
        directoryRef: String?,
    ): Int

    /** Explicit privacy erase; normal UI removal continues to use [archive]. */
    @Query("DELETE FROM sessions WHERE id = :id")
    fun deletePermanently(id: String): Int
}
