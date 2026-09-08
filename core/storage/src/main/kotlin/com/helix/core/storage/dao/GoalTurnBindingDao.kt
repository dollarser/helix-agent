package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.helix.core.storage.entity.GoalTurnBindingEntity

@Dao
interface GoalTurnBindingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(binding: GoalTurnBindingEntity)

    @Query("SELECT * FROM goal_turn_bindings WHERE turnId = :turnId")
    fun byTurn(turnId: String): GoalTurnBindingEntity?

    @Query(
        "SELECT COUNT(*) FROM goal_runs r JOIN goals g ON g.id = r.goalId " +
            "WHERE r.id = :runId AND r.endedAt IS NULL AND g.state = 'RUNNING'",
    )
    fun activeRun(runId: String): Int

    @Query(
        "SELECT COUNT(*) FROM goal_turn_bindings b " +
            "JOIN goal_runs r ON r.id = b.runId JOIN turns t ON t.id = b.turnId " +
            "WHERE r.goalId = (SELECT goalId FROM goal_runs WHERE id = :runId) " +
            "AND t.sessionId != (SELECT sessionId FROM turns WHERE id = :turnId)",
    )
    fun otherSessionBindings(
        turnId: String,
        runId: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM goal_turn_bindings b JOIN goal_runs r ON r.id = b.runId " +
            "JOIN tool_calls t ON t.turnId = b.turnId " +
            "WHERE r.goalId = :goalId AND t.state IN ('NEEDS_REVIEW', 'INTERRUPTED')",
    )
    fun unresolvedForGoal(goalId: String): Int

    @Query(
        "SELECT COUNT(*) FROM goal_turn_bindings b JOIN goal_runs r ON r.id = b.runId " +
            "JOIN tool_calls t ON t.turnId = b.turnId " +
            "WHERE r.goalId = :goalId AND t.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'DENIED')",
    )
    fun unsettledForGoal(goalId: String): Int

    @Query(
        "SELECT DISTINCT t.sessionId FROM goal_turn_bindings b " +
            "JOIN goal_runs r ON r.id = b.runId JOIN turns t ON t.id = b.turnId " +
            "WHERE r.goalId = :goalId",
    )
    fun sessionsForGoal(goalId: String): List<String>

    /** Admission and insert share one transaction, including when nested inside Turn creation. */
    @Transaction
    fun bind(
        turnId: String,
        runId: String,
    ) {
        require(activeRun(runId) == 1) { "Goal run must be open and RUNNING" }
        require(otherSessionBindings(turnId, runId) == 0) { "Goal cannot span sessions" }
        insert(GoalTurnBindingEntity(turnId, runId))
    }
}
