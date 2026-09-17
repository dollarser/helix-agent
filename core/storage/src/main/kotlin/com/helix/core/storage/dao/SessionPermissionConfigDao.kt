package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionPermissionConfigEntity

/**
 * Stored per-session permission configurations (HXA-209). Rows are written only through
 * [com.helix.core.storage.repository.SessionPermissionConfigRepository]; [insert] is a REPLACE
 * upsert under the single-column [SessionPermissionConfigEntity.sessionId] primary key (the
 * repository reads the current row first and passes the advanced revision). A session's config
 * row is removed by the repository's reset (a missing row means the session uses the app
 * default).
 */
@Dao
interface SessionPermissionConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: SessionPermissionConfigEntity)

    @Query("SELECT * FROM session_permission_configs WHERE sessionId = :sessionId")
    fun bySession(sessionId: String): SessionPermissionConfigEntity?

    @Query("DELETE FROM session_permission_configs WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String): Int
}
