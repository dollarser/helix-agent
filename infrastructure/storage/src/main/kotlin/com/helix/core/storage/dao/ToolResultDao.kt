package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolResultEntity

@Dao
interface ToolResultDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(result: ToolResultEntity)

    @Query("SELECT * FROM tool_results WHERE id = :id")
    fun byId(id: String): ToolResultEntity?

    @Query("SELECT * FROM tool_results WHERE toolCallId = :toolCallId")
    fun byToolCall(toolCallId: String): ToolResultEntity?

    @Query(
        "SELECT tool_results.contentRef FROM tool_results " +
            "JOIN tool_calls ON tool_results.toolCallId = tool_calls.id " +
            "JOIN turns ON tool_calls.turnId = turns.id " +
            "WHERE turns.sessionId = :sessionId AND tool_results.contentRef IS NOT NULL",
    )
    fun contentRefsBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM tool_results WHERE contentRef = :contentRef")
    fun countByContentRef(contentRef: String): Int

    /** Affected row count is 0 once the result is already verified. */
    @Query("UPDATE tool_results SET verified = 1 WHERE id = :id AND verified = 0")
    fun markVerified(id: String): Int
}
