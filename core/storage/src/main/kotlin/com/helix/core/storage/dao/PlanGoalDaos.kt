package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.helix.core.storage.entity.GoalEntity
import com.helix.core.storage.entity.GoalRunEntity
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.entity.PlanStepEntity

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(plan: PlanEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSteps(steps: List<PlanStepEntity>)

    /** doc 9.2: plan row and step rows land in one transaction. */
    @Transaction
    fun insertWithSteps(
        plan: PlanEntity,
        steps: List<PlanStepEntity>,
    ) {
        insert(plan)
        insertSteps(steps)
    }

    @Query("SELECT * FROM plans WHERE id = :id")
    fun byId(id: String): PlanEntity?

    @Query("SELECT * FROM plan_steps WHERE planId = :planId ORDER BY sequence ASC")
    fun stepsOf(planId: String): List<PlanStepEntity>

    @Query("SELECT * FROM plans ORDER BY rowid DESC")
    fun list(): List<PlanEntity>

    @Query("UPDATE plans SET state = :state, evidenceRef = :evidenceRef WHERE id = :id")
    fun updateState(
        id: String,
        state: String,
        evidenceRef: String?,
    )
}

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id")
    fun byId(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE state = :state ORDER BY rowid ASC")
    fun listByState(state: String): List<GoalEntity>

    @Query("SELECT * FROM goals ORDER BY rowid DESC")
    fun list(): List<GoalEntity>

    // The parameter list mirrors the normative goal recovery fields (doc 9.1 + ADR-0004);
    // Room @Query bindings require one parameter per column.
    @Suppress("LongParameterList")
    @Query(
        "UPDATE goals SET state = :state, nextCheckpoint = :nextCheckpoint, runCount = :runCount, " +
            "modelCalls = :modelCalls, toolCalls = :toolCalls, totalTokens = :totalTokens, " +
            "runTimeMillis = :runTimeMillis, currentWakeMillis = :currentWakeMillis, " +
            "retries = :retries, lastWakeReason = :lastWakeReason, error = :error, " +
            "finishReason = :finishReason WHERE id = :id",
    )
    fun updateGoal(
        id: String,
        state: String,
        nextCheckpoint: Long?,
        runCount: Int,
        modelCalls: Int,
        toolCalls: Int,
        totalTokens: Long,
        runTimeMillis: Long,
        currentWakeMillis: Long,
        retries: Int,
        lastWakeReason: String?,
        error: String?,
        finishReason: String?,
    )
}

@Dao
interface GoalRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(run: GoalRunEntity)

    @Query("SELECT * FROM goal_runs WHERE id = :id")
    fun byId(id: String): GoalRunEntity?

    @Query("SELECT * FROM goal_runs WHERE goalId = :goalId ORDER BY startedAt ASC, rowid ASC")
    fun listByGoal(goalId: String): List<GoalRunEntity>

    /** Runs still open (no end) — recovery closes them when their goal parks (HXA-015). */
    @Query(
        "SELECT * FROM goal_runs WHERE goalId = :goalId AND endedAt IS NULL " +
            "ORDER BY startedAt ASC, rowid ASC",
    )
    fun listOpenByGoal(goalId: String): List<GoalRunEntity>

    /** One-time finish: affected row count is 0 when the run already has an `endedAt`. */
    @Query(
        "UPDATE goal_runs SET outcome = :outcome, endedAt = :endedAt, wakeDurationMillis = :wakeDurationMillis, " +
            "modelCalls = :modelCalls, toolCalls = :toolCalls, tokens = :tokens " +
            "WHERE id = :id AND endedAt IS NULL",
    )
    fun updateOutcome(
        id: String,
        outcome: String?,
        endedAt: Long?,
        wakeDurationMillis: Long?,
        modelCalls: Int,
        toolCalls: Int,
        tokens: Long,
    ): Int
}
