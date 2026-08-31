package com.helix.core.storage.repository

import com.helix.core.model.GoalState
import com.helix.core.model.PlanArtifact
import com.helix.core.storage.dao.GoalDao
import com.helix.core.storage.dao.GoalRunDao
import com.helix.core.storage.dao.PlanDao
import com.helix.core.storage.entity.GoalEntity
import com.helix.core.storage.entity.GoalRunEntity
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.mapping.EntityMappers
import com.helix.core.storage.mapping.StoredGoal
import com.helix.core.storage.mapping.enumByName
import com.helix.core.storage.mapping.toGoalEntity
import com.helix.core.storage.mapping.toPlanArtifact
import com.helix.core.storage.mapping.toStoredGoal

class PlanRepository(
    private val dao: PlanDao,
) {
    /**
     * Persists a plan: normalized columns + steps + the ADR-0001 canonical `storage` column,
     * all in one transaction (doc 9.2). [state] is the plan lifecycle state name.
     */
    fun save(
        artifact: PlanArtifact,
        state: String,
        evidenceRef: String?,
    ): PlanEntity {
        enumByName(state, PlanLifecycleState::class.java, "plan state")
        val entity = EntityMappers.planEntityFor(artifact, state, evidenceRef)
        dao.insertWithSteps(entity, EntityMappers.planStepsFor(artifact))
        return entity
    }

    /**
     * Recovers the plan from the normalized columns and step rows, verifying the stored hash
     * (ADR-0001 recovery contract for a writer-only type).
     */
    fun resolve(id: String): PlanArtifact {
        val entity =
            dao.byId(id) ?: throw IllegalArgumentException("plan not found: $id")
        return entity.toPlanArtifact(dao.stepsOf(entity.id))
    }

    fun resolveEntity(id: String): PlanEntity = dao.byId(id) ?: throw IllegalArgumentException("plan not found: $id")

    fun list(): List<PlanEntity> = dao.list()

    fun updateState(
        id: String,
        state: String,
        evidenceRef: String?,
    ) {
        enumByName(state, PlanLifecycleState::class.java, "plan state")
        dao.updateState(id, state, evidenceRef)
    }
}

/** Plan lifecycle states persisted in `plans.state` (doc 9.1: `state`). */
enum class PlanLifecycleState {
    DRAFT,
    READY,
    APPROVED,
    EXECUTING,
    SUPERSEDED,
    REJECTED,
}

class GoalRepository(
    private val dao: GoalDao,
) {
    fun save(goal: StoredGoal): GoalEntity {
        require(goal.objective.isNotBlank() && goal.objective.length <= MAX_OBJECTIVE_LENGTH) {
            "objective must be 1..$MAX_OBJECTIVE_LENGTH non-blank chars"
        }
        enumByName(goal.state, GoalState::class.java, "goal state")
        require(goal.correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(goal.criteria.size <= 32) { "a goal holds at most 32 criteria" }
        dao.insert(goal.toGoalEntity())
        return resolveEntity(goal.id)
    }

    fun resolve(id: String): StoredGoal {
        val entity = dao.byId(id) ?: throw IllegalArgumentException("goal not found: $id")
        return entity.toStoredGoal()
    }

    // toStoredGoal re-parses budgets/criteria/error, so it doubles as a storage-integrity check.

    fun resolveEntity(id: String): GoalEntity = dao.byId(id) ?: throw IllegalArgumentException("goal not found: $id")

    fun list(): List<GoalEntity> = dao.list()

    fun listByState(state: String): List<GoalEntity> = dao.listByState(state)

    /**
     * Whole-row goal update used by the recovery coordinator (HXA-015); [goal] carries the
     * counters after the applied events.
     */
    fun updateGoal(goal: StoredGoal) {
        enumByName(goal.state, GoalState::class.java, "goal state")
        dao.updateGoal(
            id = goal.id,
            state = goal.state,
            nextCheckpoint = goal.nextCheckpoint,
            runCount = goal.runCount,
            modelCalls = goal.modelCalls,
            toolCalls = goal.toolCalls,
            totalTokens = goal.totalTokens,
            runTimeMillis = goal.runTimeMillis,
            currentWakeMillis = goal.currentWakeMillis,
            retries = goal.retries,
            lastWakeReason = goal.lastWakeReason,
            error = goal.error?.toStorageString(),
            finishReason = goal.finishReason,
        )
    }

    private companion object {
        const val MAX_OBJECTIVE_LENGTH = 1024
    }
}

class GoalRunRepository(
    private val dao: GoalRunDao,
) {
    fun open(
        id: String,
        goalId: String,
        wakeReason: String,
        startedAt: Long,
    ): GoalRunEntity {
        require(wakeReason.isNotBlank()) { "wakeReason must not be blank" }
        require(startedAt >= 0) { "startedAt must be >= 0" }
        val entity = GoalRunEntity(id, goalId, wakeReason, null, startedAt, null, null, 0, 0, 0)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): GoalRunEntity = dao.byId(id) ?: throw IllegalArgumentException("goal run not found: $id")

    fun listByGoal(goalId: String): List<GoalRunEntity> = dao.listByGoal(goalId)

    fun finish(
        run: GoalRunEntity,
        outcome: String,
        endedAt: Long,
        wakeDurationMillis: Long,
        modelCalls: Int,
        toolCalls: Int,
        tokens: Long,
    ) {
        require(outcome.isNotBlank()) { "outcome must not be blank" }
        require(endedAt >= run.startedAt) { "endedAt must be >= startedAt" }
        require(wakeDurationMillis >= 0) { "wakeDurationMillis must be >= 0" }
        require(modelCalls >= 0 && toolCalls >= 0 && tokens >= 0) { "run usage must be >= 0" }
        dao.updateOutcome(run.id, outcome, endedAt, wakeDurationMillis, modelCalls, toolCalls, tokens)
    }
}
