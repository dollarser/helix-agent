package com.helix.app.goal

import com.helix.core.agent.Checkpoint
import com.helix.core.agent.Criterion
import com.helix.core.agent.Goal
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.CorrelationId
import com.helix.core.model.GoalId
import com.helix.core.model.GoalState
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.mapping.StoredGoal

internal fun StoredGoal.toRuntimeGoal(): Goal =
    Goal(
        id = GoalId(id),
        objective = objective,
        criteria = criteria.map { Criterion(it.id, it.description) },
        state = GoalState.valueOf(state),
        planId = planId?.let(::PlanId),
        planHash = planHash?.let(::Sha256),
        budgets = budgets,
        nextCheckpoint = nextCheckpoint?.let(::Checkpoint),
        correlationId = CorrelationId(correlationId),
        runCount = runCount,
        modelCalls = modelCalls,
        toolCalls = toolCalls,
        totalTokens = totalTokens,
        runTimeMillis = runTimeMillis,
        currentWakeMillis = currentWakeMillis,
        retries = retries,
        lastWakeReason = lastWakeReason?.let(GoalWakeReason::valueOf),
        error = error,
        // Legacy ledger rows also carry a pause label here. The durable run outcome retains it;
        // the domain reserves finishReason for terminal goals (ADR-0004).
        finishReason = finishReason.takeIf { GoalState.valueOf(state).isTerminal },
    )

internal fun Goal.toStoredGoal(): StoredGoal =
    StoredGoal(
        id = id.value,
        objective = objective,
        criteria = criteria.map { StoredCriterion(it.id, it.description, null) },
        state = state.name,
        planId = planId?.value,
        planHash = planHash?.hex,
        budgets = budgets,
        nextCheckpoint = nextCheckpoint?.atEpochMillis,
        correlationId = correlationId.value,
        runCount = runCount,
        modelCalls = modelCalls,
        toolCalls = toolCalls,
        totalTokens = totalTokens,
        runTimeMillis = runTimeMillis,
        currentWakeMillis = currentWakeMillis,
        retries = retries,
        lastWakeReason = lastWakeReason?.name,
        error = error,
        finishReason = finishReason,
    )
