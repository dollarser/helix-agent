package com.helix.core.agent

import com.helix.core.model.CorrelationId
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.HelixError
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256

/**
 * Real wake source recorded into the `goal_runs` table (modes doc section 6.1).
 *
 * v1 admits only the two explicit user wakes ([USER_OPEN], [NOTIFICATION_ACTION]); WorkManager
 * reminders may be delayed or dropped (Doze, force-stop) and never wake the goal by themselves.
 *
 * The remaining three values are the wake types the [GoalDriver] (HX2-08) makes admission
 * policy for — the "should we attempt a run?" layer above the reducer's "can a run start?" —
 * and are gated off in the v1 driver policy ([GoalWakePolicy.V1]) until their product loops
 * (foreground continuation, scheduled checkpoints, channels) land. They live in this enum so
 * `goal_runs` can record them once enabled, without the reducer ever special-casing them
 * (research doc section 32: adding Cron/Channel/A2A/push must not pollute the Goal reducer).
 */
enum class GoalWakeReason {
    USER_OPEN,
    NOTIFICATION_ACTION,
    FOREGROUND_CONTINUATION,
    SCHEDULED_CHECKPOINT,
    CHANNEL_EVENT,
}

/**
 * Reducer state of a persistent Goal (modes doc section 6.1/6.2). Mirrors the documented
 * `Goal` value (id/objective/acceptanceCriteria/state/planId/budgets/nextCheckpoint) and adds
 * the lifetime accounting the reducer needs: runs, model/tool calls, tokens, durations,
 * retries. Consumed usage accumulates across wakes; the per-turn limits are derived from the
 * remaining goal budget by [GoalEffect.StartRun].
 *
 * Process death parks a RUNNING goal in [GoalState.PAUSED] (durable park; resume requires an
 * explicit user continue with a real [GoalWakeReason]). The model reports semantic completion;
 * the host checks execution state. Budget exhaustion parks the goal in
 * BLOCKED (never COMPLETED); a wake failure exhausts [GoalBudgets.maxRetries] into FAILED.
 */
data class Goal(
    val id: GoalId,
    val objective: String,
    val criteria: List<Criterion>,
    val state: GoalState,
    val planId: PlanId?,
    val planHash: Sha256?,
    val budgets: GoalBudgets,
    val nextCheckpoint: Checkpoint?,
    val correlationId: CorrelationId,
    val runCount: Int = 0,
    val modelCalls: Int = 0,
    val toolCalls: Int = 0,
    val totalTokens: Long = 0,
    val runTimeMillis: Long = 0,
    val currentWakeMillis: Long = 0,
    val retries: Int = 0,
    val lastWakeReason: GoalWakeReason? = null,
    val error: HelixError? = null,
    val finishReason: String? = null,
) {
    init {
        require(objective.isNotBlank()) { "objective must not be blank" }
        require(objective.length <= MAX_OBJECTIVE_LENGTH) {
            "objective must be <= $MAX_OBJECTIVE_LENGTH characters"
        }
        require(criteria.size <= MAX_CRITERIA) { "criteria must have <= $MAX_CRITERIA items" }
        val ids = criteria.map { it.id }
        require(ids.distinct().size == ids.size) { "criterion ids must be unique" }
        require((planId == null) == (planHash == null)) {
            "planId and planHash must be set together"
        }
        require(modelCalls >= 0 && toolCalls >= 0) { "call counters must be >= 0" }
        require(totalTokens >= 0 && runTimeMillis >= 0 && currentWakeMillis >= 0) {
            "usage accounting must be >= 0"
        }
        require(runCount >= 0 && retries >= 0) { "run/retry counters must be >= 0" }
    }

    val isTerminal: Boolean
        get() = state.isTerminal

    fun remainingModelCalls(): Int = budgets.maxModelCalls - modelCalls

    fun remainingToolCalls(): Int = budgets.maxToolCalls - toolCalls

    fun remainingTotalTokens(): Long = budgets.maxTotalTokens - totalTokens

    /**
     * Whether the remaining goal budget can still be materialized as a legal per-run
     * [com.helix.core.model.TurnBudgets] (ADR-0004 item 1): at least one model call, one tool
     * call and one token of headroom, plus lifetime and single-wake duration headroom.
     *
     * Single source of truth shared by the reducer's [GoalEvent.Continued] acceptance and the
     * [GoalDriver] pre-flight, so the "can a run start?" and "should we attempt one?" layers
     * never diverge.
     */
    fun hasRunBudgetHeadroom(): Boolean =
        remainingModelCalls() >= 1 && remainingToolCalls() >= 1 && remainingTotalTokens() >= 1 &&
            runTimeMillis < budgets.maxDurationMillis && budgets.maxWakeDurationMillis > 0

    companion object {
        const val MAX_OBJECTIVE_LENGTH = 1024
        const val MAX_CRITERIA = 32

        fun initial(
            id: GoalId,
            objective: String,
            criteria: List<Criterion>,
            budgets: GoalBudgets,
            correlationId: CorrelationId,
            planId: PlanId? = null,
            planHash: Sha256? = null,
        ): Goal =
            Goal(
                id,
                objective,
                criteria,
                GoalState.DRAFT,
                planId,
                planHash,
                budgets,
                null,
                correlationId,
            )
    }
}
