package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.helix.core.storage.entity.DocumentSnapshotEntity

@Dao
interface DocumentSnapshotDao {
    @Insert fun insert(entity: DocumentSnapshotEntity)

    @Query("SELECT * FROM document_snapshots WHERE id = :id AND sessionId = :sessionId")
    fun find(
        sessionId: String,
        id: String,
    ): DocumentSnapshotEntity?

    @Query("SELECT * FROM document_snapshots WHERE sessionId = :sessionId ORDER BY rowid DESC")
    fun listBySession(sessionId: String): List<DocumentSnapshotEntity>

    @Query("SELECT contentRef FROM document_snapshots WHERE sessionId = :sessionId")
    fun contentRefsBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM document_snapshots WHERE contentRef = :contentRef")
    fun countByContentRef(contentRef: String): Int
}
