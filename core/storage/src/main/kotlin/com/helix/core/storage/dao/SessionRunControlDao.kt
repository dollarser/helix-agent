package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionRunControlEntity

@Dao
interface SessionRunControlDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: SessionRunControlEntity)

    @Query("SELECT * FROM session_run_controls WHERE sessionId = :sessionId")
    fun bySession(sessionId: String): SessionRunControlEntity?
}
