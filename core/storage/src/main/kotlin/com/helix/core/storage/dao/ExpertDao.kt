package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ExpertEntity

@Dao
interface ExpertDao {
    @Query("SELECT * FROM experts ORDER BY displayName, id")
    fun list(): List<ExpertEntity>

    @Query("SELECT * FROM experts WHERE id = :id")
    fun find(id: String): ExpertEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(entity: ExpertEntity)
}
