package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.SessionExpertEntity

@Dao
interface SessionExpertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: SessionExpertEntity)

    @Query("SELECT * FROM session_experts WHERE sessionId = :sessionId")
    fun bySession(sessionId: String): SessionExpertEntity?

    @Query("DELETE FROM session_experts WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String): Int
}
