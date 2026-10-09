package com.helix.app.recovery

import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.StoredGoal
import java.util.UUID

/**
 * Atomic, monotonic Goal usage accounting required by ADR-0004.
 *
 * The run row, goal lifetime counters and redacted audit event commit in one Room transaction.
 * Each supplied duration delta is bounded by [MAX_UNACCOUNTED_MILLIS]. This validation alone
 * does not prove a bounded crash window: production callers must persist admission before
 * execution and reconcile outstanding usage. Post-call checkpoints can be lost before commit.
 */
class GoalDurableUsageLedger(
    private val storage: HelixStorage,
) {
    enum class Boundary {
        MODEL,
        TOOL,
        SIDE_EFFECT,
        HEARTBEAT,
    }

    data class Delta(
        val modelCalls: Int = 0,
        val toolCalls: Int = 0,
        val tokens: Long = 0,
        val durationMillis: Long = 0,
    )

    fun checkpoint(
        goalId: String,
        runId: String,
        boundary: Boundary,
        delta: Delta,
        atMillis: Long,
    ) {
        validate(boundary, delta, atMillis)
        storage.withTransaction { persist(goalId, runId, boundary, delta, atMillis) }
    }

    private fun validate(
        boundary: Boundary,
        delta: Delta,
        atMillis: Long,
    ) {
        require(atMillis >= 0) { "atMillis must be >= 0" }
        require(delta.modelCalls >= 0 && delta.toolCalls >= 0 && delta.tokens >= 0 && delta.durationMillis >= 0) {
            "usage delta must be non-negative"
        }
        require(delta.durationMillis <= MAX_UNACCOUNTED_MILLIS) {
            "duration checkpoint exceeds $MAX_UNACCOUNTED_MILLIS ms"
        }
        require(boundary != Boundary.MODEL || delta.modelCalls == 1) { "MODEL boundary accounts exactly one call" }
        require(boundary != Boundary.TOOL || delta.toolCalls == 1) { "TOOL boundary accounts exactly one call" }
        require(boundary != Boundary.HEARTBEAT || delta.modelCalls + delta.toolCalls == 0) {
            "HEARTBEAT cannot account calls"
        }
    }

    private fun persist(
        goalId: String,
        runId: String,
        boundary: Boundary,
        delta: Delta,
        atMillis: Long,
    ) {
        val goal = storage.goals.resolve(goalId)
        val run = storage.goalRuns.resolve(runId)
        require(run.goalId == goalId) { "run does not belong to goal" }
        require(goal.state == GoalState.RUNNING.name) { "goal is not RUNNING" }
        val next = add(goal, delta)
        storage.goals.updateGoal(next)
        storage.goalRuns.checkpointUsage(
            run,
            Math.addExact(run.modelCalls, delta.modelCalls),
            Math.addExact(run.toolCalls, delta.toolCalls),
            Math.addExact(run.tokens, delta.tokens),
            Math.addExact(run.wakeDurationMillis ?: 0L, delta.durationMillis),
        )
        appendAudit(goal, goalId, runId, boundary, delta, null, atMillis)
    }

    private fun add(
        goal: StoredGoal,
        delta: Delta,
    ): StoredGoal =
        goal.copy(
            modelCalls = Math.addExact(goal.modelCalls, delta.modelCalls),
            toolCalls = Math.addExact(goal.toolCalls, delta.toolCalls),
            totalTokens = Math.addExact(goal.totalTokens, delta.tokens),
            runTimeMillis = Math.addExact(goal.runTimeMillis, delta.durationMillis),
            currentWakeMillis = Math.addExact(goal.currentWakeMillis, delta.durationMillis),
        )

    @Suppress("LongParameterList")
    private fun appendAudit(
        goal: StoredGoal,
        goalId: String,
        runId: String,
        boundary: Boundary,
        delta: Delta,
        exhausted: String?,
        atMillis: Long,
    ) {
        storage.auditEvents.append(
            id = "goal-usage-${UUID.randomUUID()}",
            correlationId = goal.correlationId,
            type = "goal.usage_checkpoint",
            actor = "SYSTEM",
            redactedPayload =
                "{\"goal\":\"$goalId\",\"run\":\"$runId\",\"boundary\":\"${boundary.name}\"," +
                    "\"modelCalls\":${delta.modelCalls},\"toolCalls\":${delta.toolCalls}," +
                    "\"tokens\":${delta.tokens},\"durationMillis\":${delta.durationMillis}," +
                    "\"exhausted\":${exhausted?.let { "\"$it\"" } ?: "null"}}",
            timestamp = atMillis,
        )
    }

    companion object {
        const val MAX_UNACCOUNTED_MILLIS: Long = 5_000
    }
}
