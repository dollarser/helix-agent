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

    @Query("SELECT * FROM artifacts WHERE id = :id")
    fun byId(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId ORDER BY rowid ASC")
    fun listBySession(sessionId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId AND relativePath = :relativePath LIMIT 1")
    fun bySessionAndPath(
        sessionId: String,
        relativePath: String,
    ): ArtifactEntity?

    @Query("SELECT COUNT(*) FROM artifacts WHERE relativePath = :relativePath")
    fun countByRelativePath(relativePath: String): Int
}
