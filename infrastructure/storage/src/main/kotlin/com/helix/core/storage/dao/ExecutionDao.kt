package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ExecutionEntity

@Dao
interface ExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(execution: ExecutionEntity)

    @Query("SELECT * FROM executions WHERE id = :id")
    fun byId(id: String): ExecutionEntity?

    @Query("SELECT * FROM executions WHERE toolCallId = :toolCallId")
    fun byToolCall(toolCallId: String): ExecutionEntity?

    @Query("UPDATE executions SET exitCode = :exitCode, signal = :signal WHERE id = :id")
    fun updateOutcome(
        id: String,
        exitCode: Int?,
        signal: String?,
    )
}
