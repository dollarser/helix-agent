package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ModelCallEntity

@Dao
interface ModelCallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(call: ModelCallEntity)

    @Query("SELECT * FROM model_calls WHERE id = :id")
    fun byId(id: String): ModelCallEntity?

    @Query("SELECT * FROM model_calls WHERE turnId = :turnId ORDER BY rowid ASC")
    fun listByTurn(turnId: String): List<ModelCallEntity>

    @Query(
        "UPDATE model_calls SET state = 'INTERRUPTED' WHERE state = 'RUNNING' " +
            "AND turnId IN (SELECT id FROM turns WHERE state = 'INTERRUPTED')",
    )
    fun interruptForInterruptedTurns(): Int

    @Query("UPDATE model_calls SET state = :state, usage = :usage, requestId = :requestId WHERE id = :id")
    fun update(
        id: String,
        state: String,
        usage: String?,
        requestId: String?,
    )
}
