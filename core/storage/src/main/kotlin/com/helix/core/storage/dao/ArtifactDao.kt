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
     * Tool-write registration (v15) — the refresh half: the file row is stable per
     * (sessionId, relativePath), so re-writing the same path updates the content columns of
     * the EXISTING row and keeps its id; `message_attachments` bound to that artifact survive
     * a re-write (their boundSha256 then fails closed on the changed content, by design).
     * Returns the rows updated (0 or 1); the [com.helix.core.storage.repository.ArtifactRepository]
     * inserts only when 0, because the single-statement `ON CONFLICT ... DO UPDATE` upsert
     * that would express both halves needs SQLite 3.24 and minSdk 29 AOSP images ship 3.22.
     * NOT `INSERT OR REPLACE` on the insert path either: that would delete the old row and
     * cascade its attachments.
     */
    @Query(
        "UPDATE artifacts SET mediaType = :mediaType, size = :size, " +
            "sha256 = :sha256, turnId = :turnId " +
            "WHERE sessionId = :sessionId AND relativePath = :relativePath",
    )
    fun refreshBySessionAndPath(
        sessionId: String,
        relativePath: String,
        mediaType: String,
        size: Long,
        sha256: String,
        turnId: String?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE id = :id")
    fun byId(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId ORDER BY rowid ASC")
    fun listBySession(sessionId: String): List<ArtifactEntity>

    /**
     * The artifacts of one turn by REAL ownership (HXA-202): the rows whose `turnId` is the
     * turn that last wrote them. Unlike [recent] there is no cross-session truncation, so a
     * task's files are never lost behind the artifact center's window.
     */
    @Query("SELECT * FROM artifacts WHERE turnId = :turnId ORDER BY rowid ASC")
    fun listByTurn(turnId: String): List<ArtifactEntity>

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
