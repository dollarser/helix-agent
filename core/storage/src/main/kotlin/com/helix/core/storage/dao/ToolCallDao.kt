package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolCallEntity

@Dao
interface ToolCallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(call: ToolCallEntity)

    @Query("SELECT * FROM tool_calls WHERE id = :id")
    fun byId(id: String): ToolCallEntity?

    @Query("SELECT * FROM tool_calls WHERE turnId = :turnId ORDER BY rowid ASC")
    fun listByTurn(turnId: String): List<ToolCallEntity>

    @Query("SELECT * FROM tool_calls WHERE turnId = :turnId AND callId = :callId")
    fun byTurnAndCallId(
        turnId: String,
        callId: String,
    ): ToolCallEntity?

    @Query("UPDATE tool_calls SET state = :state WHERE id = :id")
    fun updateState(
        id: String,
        state: String,
    )
}
