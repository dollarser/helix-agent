package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.GoalUsageReservationEntity

@Dao
interface GoalUsageReservationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(value: GoalUsageReservationEntity)

    @Query("SELECT * FROM goal_usage_reservations WHERE id = :id")
    fun byId(id: String): GoalUsageReservationEntity?

    @Query("SELECT * FROM goal_usage_reservations WHERE runId = :runId AND state = 'PENDING' ORDER BY rowid")
    fun pendingForRun(runId: String): List<GoalUsageReservationEntity>

    @Query(
        "UPDATE goal_usage_reservations SET state = :state, chargedTokens = :tokens, chargedMillis = :millis " +
            "WHERE id = :id AND state = 'PENDING'",
    )
    fun settle(
        id: String,
        state: String,
        tokens: Long,
        millis: Long,
    ): Int
}
