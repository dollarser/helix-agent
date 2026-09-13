package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ArtifactEntity

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(artifact: ArtifactEntity)

    /**
     * Tool-write registration (v15): the file row is stable per (sessionId, relativePath) —
     * re-writing the same path refreshes the content columns of the EXISTING row and keeps its
     * id, so `message_attachments` bound to that artifact survive a re-write (their boundSha256
     * then fails closed on the changed content, by design). Only a brand-new path inserts,
     * where [id] is used. NOT `INSERT OR REPLACE`: that would delete the old row and cascade
     * its attachments.
     */
    @Query(
        "INSERT INTO artifacts (id, sessionId, relativePath, mediaType, size, sha256, turnId) " +
            "VALUES (:id, :sessionId, :relativePath, :mediaType, :size, :sha256, :turnId) " +
            "ON CONFLICT (sessionId, relativePath) DO UPDATE SET " +
            "mediaType = excluded.mediaType, size = excluded.size, " +
            "sha256 = excluded.sha256, turnId = excluded.turnId",
    )
    fun upsertBySessionAndPath(
        id: String,
        sessionId: String,
        relativePath: String,
        mediaType: String,
        size: Long,
        sha256: String,
        turnId: String?,
    )

    @Query("SELECT * FROM artifacts WHERE id = :id")
    fun byId(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId ORDER BY rowid ASC")
    fun listBySession(sessionId: String): List<ArtifactEntity>

    /**
     * The newest-registered artifacts across sessions (the artifact center's files section,
     * doc 02 §8). `rowid` is registration order: a re-write refreshes the row in place and
     * keeps its position (upsert, not delete+insert), so the list orders by FIRST write of
     * a path, which is the honest reading of "when did this file appear".
     */
    @Query("SELECT * FROM artifacts ORDER BY rowid DESC LIMIT :limit")
    fun recent(limit: Int): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId AND relativePath = :relativePath LIMIT 1")
    fun bySessionAndPath(
        sessionId: String,
        relativePath: String,
    ): ArtifactEntity?

    @Query("SELECT COUNT(*) FROM artifacts WHERE relativePath = :relativePath")
    fun countByRelativePath(relativePath: String): Int
}
