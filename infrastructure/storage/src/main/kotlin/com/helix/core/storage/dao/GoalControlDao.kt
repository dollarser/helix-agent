package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.GoalControlEntity

@Dao
interface GoalControlDao {
    @Query("SELECT * FROM goal_controls WHERE pendingTurnId IS NOT NULL")
    fun allPending(): List<GoalControlEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(control: GoalControlEntity)

    @Query("SELECT * FROM goal_controls WHERE goalId = :id")
    fun find(id: String): GoalControlEntity?

    @Query("SELECT * FROM goal_controls WHERE sessionId = :sessionId ORDER BY rowid DESC")
    fun bySession(sessionId: String): List<GoalControlEntity>

    @Query("SELECT * FROM goal_controls WHERE pendingTurnId = :turnId")
    fun pendingForTurn(turnId: String): List<GoalControlEntity>

    @Query(
        "UPDATE goal_controls SET revision = revision + 1, pendingTurnId = :turnId, " +
            "pendingJson = :json WHERE goalId = :id AND revision = :revision",
    )
    fun stage(
        id: String,
        revision: Long,
        turnId: String?,
        json: String?,
    ): Int

    @Query(
        "UPDATE goal_controls SET revision = revision + 1, pendingTurnId = NULL, pendingJson = NULL " +
            "WHERE goalId = :id AND revision = :revision",
    )
    fun settle(
        id: String,
        revision: Long,
    ): Int
}
