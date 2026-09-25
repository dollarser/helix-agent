package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.TurnRuntimeRecordEntity

@Dao
interface TurnRuntimeRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(record: TurnRuntimeRecordEntity)

    @Query("SELECT * FROM turn_runtime_records WHERE turnId = :turnId")
    fun byTurn(turnId: String): TurnRuntimeRecordEntity?

    @Query(
        "UPDATE turn_runtime_records " +
            "SET consumedModelCalls = consumedModelCalls + 1 " +
            "WHERE turnId = :turnId AND consumedModelCalls = :expected",
    )
    fun incrementModelCalls(
        turnId: String,
        expected: Int,
    ): Int

    @Query(
        "UPDATE turn_runtime_records SET consumedTokens = :total " +
            "WHERE turnId = :turnId AND consumedTokens = :expected",
    )
    fun compareAndSetTokens(
        turnId: String,
        expected: Long,
        total: Long,
    ): Int

    @Query(
        "UPDATE turn_runtime_records " +
            "SET admittedToolRounds = admittedToolRounds + 1 " +
            "WHERE turnId = :turnId AND admittedToolRounds = :expected",
    )
    fun incrementToolRounds(
        turnId: String,
        expected: Int,
    ): Int
}
