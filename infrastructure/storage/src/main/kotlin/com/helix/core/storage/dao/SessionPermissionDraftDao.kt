package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionPermissionDraftEntity

/**
 * Stored per-session CUSTOM permission drafts (HXA-209 D, ADR-PERMISSIONS-001 section 4). Rows
 * are written only through [com.helix.core.storage.repository.SessionPermissionConfigRepository];
 * [insert] is a REPLACE upsert under the single-column [SessionPermissionDraftEntity.sessionId]
 * primary key. A session's draft row is removed by the repository's clear (a missing row means
 * the session has no custom snapshot yet).
 */
@Dao
interface SessionPermissionDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: SessionPermissionDraftEntity)

    @Query("SELECT * FROM session_permission_drafts WHERE sessionId = :sessionId")
    fun bySession(sessionId: String): SessionPermissionDraftEntity?

    @Query("DELETE FROM session_permission_drafts WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String): Int
}
