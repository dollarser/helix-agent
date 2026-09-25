package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolCallReviewEntity

@Dao
interface ToolCallReviewDao {
    /**
     * First writer wins. A duplicate insert is resolved by the repository from durable truth,
     * so same-decision re-drives stay idempotent and conflicting decisions return a stable
     * domain conflict instead of leaking a SQLite uniqueness exception.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(review: ToolCallReviewEntity): Long

    @Query("SELECT * FROM tool_call_reviews WHERE toolCallId = :toolCallId")
    fun byToolCallId(toolCallId: String): ToolCallReviewEntity?

    @Query("SELECT * FROM tool_call_reviews WHERE toolCallId IN (:toolCallIds)")
    fun byToolCallIds(toolCallIds: List<String>): List<ToolCallReviewEntity>

    @Query(
        "SELECT r.* FROM tool_call_reviews r " +
            "INNER JOIN tool_calls c ON r.toolCallId = c.id " +
            "WHERE c.turnId = :turnId",
    )
    fun listByTurn(turnId: String): List<ToolCallReviewEntity>
}
