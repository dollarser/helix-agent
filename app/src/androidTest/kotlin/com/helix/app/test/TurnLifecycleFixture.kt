package com.helix.app.test

import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.TurnSteeringDraft
import com.helix.app.application.agent.GoalRunCoordinator
import com.helix.app.application.agent.GoalTurnStart
import com.helix.app.application.agent.StartedGoalTurn
import com.helix.app.engine.TurnAdmissionResult
import com.helix.app.engine.TurnEngine
import com.helix.core.agent.Checkpoint
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import java.util.WeakHashMap

// Test construction metadata only, keyed by object identity. Never a source of durable Turn facts.
// Weak keys allow completed fixture metadata to be collected; no fallback to guessed/global context.
private val fixtureOwners = WeakHashMap<TurnCoordinator, TurnEngine>()

internal fun TurnCoordinator.bindFixtureLifecycle(engine: TurnEngine): TurnCoordinator =
    apply {
        synchronized(fixtureOwners) {
            val previous = fixtureOwners[this]
            check(previous == null || previous === engine) { "fixture lifecycle already bound" }
            fixtureOwners[this] = engine
        }
    }

internal fun TurnAdmissionResult.bindFixtureLifecycle(engine: TurnEngine): TurnAdmissionResult =
    apply {
        if (this is TurnAdmissionResult.Started) turn.coordinator.bindFixtureLifecycle(engine)
    }

private fun fixtureOwner(coordinator: TurnCoordinator): TurnEngine =
    synchronized(fixtureOwners) {
        checkNotNull(fixtureOwners[coordinator]) { "unregistered fixture lifecycle" }
    }

internal fun startFixtureTurn(
    storage: HelixStorage,
    clock: Clock,
    idGenerator: () -> String,
    spec: TurnStartSpec,
): TurnCoordinator =
    TurnCoordinator
        .start(
            storage,
            clock,
            idGenerator,
            spec,
        ).bindFixtureLifecycle(TurnEngine(storage, clock, idGenerator))

/** Thin test preparation adapter: original Goal implementation, original clock and ID closure. */
internal class FixtureGoalRunCoordinator(
    storage: HelixStorage,
    clock: Clock,
    idGenerator: () -> String,
) {
    private val delegate = GoalRunCoordinator(storage, clock, idGenerator)
    private val engine = TurnEngine(storage, clock, idGenerator)

    fun create(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
        planId: PlanId? = null,
        planHash: Sha256? = null,
    ): String = delegate.create(objective, criteria, budgets, planId, planHash)

    fun saveReadyGoal(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
        planId: PlanId?,
        planHash: Sha256?,
    ): String = delegate.saveReadyGoal(objective, criteria, budgets, planId, planHash)

    fun start(request: GoalTurnStart): StartedGoalTurn? =
        delegate.start(request)?.also {
            it.coordinator.bindFixtureLifecycle(engine)
        }

    fun updateBudgets(
        goalId: String,
        budgets: GoalBudgets,
        expectedRevision: Long? = null,
    ): Boolean = delegate.updateBudgets(goalId, budgets, expectedRevision)

    fun setCheckpoint(
        goalId: String,
        checkpoint: Checkpoint?,
    ): Boolean = delegate.setCheckpoint(goalId, checkpoint)
}

internal fun TurnCoordinator.settleFixtureTerminal(outcome: ModelStreamTerminal) {
    val coordinator = this
    val engine = fixtureOwner(coordinator)
    runBlocking { engine.settleTerminal(coordinator, outcome) }
}

internal fun TurnCoordinator.parkFixtureForReview(reviewCallIds: List<String>) {
    fixtureOwner(this).parkForReview(this, terminalCheckpoint().sessionId, reviewCallIds)
}

internal fun TurnCoordinator.completeResponseOrContinueFixture(
    prepared: TurnSteeringDraft?,
    nextModelCallId: String,
): ResponseInputBoundary =
    completeResponseOrContinue(prepared, nextModelCallId, fixtureOwner(this)::persistTerminalInTransaction)
