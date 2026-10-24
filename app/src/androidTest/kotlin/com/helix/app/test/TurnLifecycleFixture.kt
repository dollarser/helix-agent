package com.helix.app.test

import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.TurnSteeringDraft
import com.helix.app.agent.toRoundInput
import com.helix.app.application.agent.GoalRunCoordinator
import com.helix.app.application.agent.GoalTurnStart
import com.helix.app.application.agent.StartedGoalTurn
import com.helix.app.infrastructure.agent.RoomTurnRoundStore
import com.helix.app.infrastructure.agent.createRoomTurnEngine
import com.helix.core.agent.Checkpoint
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import java.util.WeakHashMap

// Test construction metadata only, keyed by object identity. Never a source of durable Turn facts.
// Weak keys allow completed fixture metadata to be collected; no fallback to guessed/global context.
private val fixtureOwners = WeakHashMap<TurnRoundCoordinator, TurnEngine>()

internal fun TurnRoundCoordinator.bindFixtureLifecycle(engine: TurnEngine): TurnRoundCoordinator =
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

private fun fixtureOwner(coordinator: TurnRoundCoordinator): TurnEngine =
    synchronized(fixtureOwners) {
        checkNotNull(fixtureOwners[coordinator]) { "unregistered fixture lifecycle" }
    }

internal fun startFixtureTurn(
    storage: HelixStorage,
    clock: Clock,
    idGenerator: () -> String,
    spec: TurnStartSpec,
): TurnRoundCoordinator =
    RoomTurnRoundStore
        .start(
            storage,
            clock,
            idGenerator,
            spec,
        ).bindFixtureLifecycle(
            createRoomTurnEngine(
                storage,
                clock,
                idGenerator,
                recoveryFailureNotice = { "Synthetic recovery unavailable" },
            ),
        )

/** Thin test preparation adapter: original Goal implementation, original clock and ID closure. */
internal class FixtureGoalRunCoordinator(
    storage: HelixStorage,
    clock: Clock,
    idGenerator: () -> String,
) {
    private val delegate = GoalRunCoordinator(storage, clock, idGenerator)
    private val engine =
        createRoomTurnEngine(storage, clock, idGenerator, recoveryFailureNotice = { "Synthetic recovery unavailable" })

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

internal fun TurnRoundCoordinator.settleFixtureTerminal(outcome: ModelStreamTerminal) {
    val coordinator = this
    val engine = fixtureOwner(coordinator)
    runBlocking { engine.settleTerminal(coordinator, outcome) }
}

internal fun TurnRoundCoordinator.parkFixtureForReview(reviewCallIds: List<String>) {
    fixtureOwner(this).parkForReview(this, terminalCheckpoint().sessionId, reviewCallIds)
}

internal fun TurnRoundCoordinator.completeResponseOrContinueFixture(
    prepared: TurnSteeringDraft?,
    nextModelCallId: String,
): ResponseInputBoundary = completeResponseOrContinue(prepared?.toRoundInput(), nextModelCallId)

/** Fixture preparation only: constructs the same closed value command as the application owner. */
internal fun fixtureAdmissionCommand(
    spec: TurnStartSpec,
    control: com.helix.core.agent.RunControlConfig,
    wakeReason: com.helix.core.agent.GoalWakeReason,
    providerId: String,
    modelId: String,
    expectedSessionModelId: String? = modelId,
    goal: com.helix.core.agent.turn.TurnGoalIntent = com.helix.core.agent.turn.TurnGoalIntent.None,
): com.helix.core.agent.turn.TurnAdmissionCommand =
    com.helix.core.agent.turn.TurnAdmissionCommand(
        identity =
            com.helix.core.agent.turn.TurnAdmissionIdentity(
                spec.sessionId,
                spec.turnId,
                spec.firstModelCallId,
                spec.clientRequestId,
                spec.inputFingerprint,
            ),
        provider =
            com.helix.core.agent.turn
                .TurnModelBinding(providerId, modelId, spec.providerSnapshot),
        input =
            com.helix.core.agent.turn.TurnAdmissionInput(
                spec.userText,
                spec.attachments.map {
                    com.helix.core.agent.turn.TurnInputAttachment(
                        it.artifactId,
                        it.purpose,
                        it.boundSha256,
                    )
                },
                spec.references.map {
                    com.helix.core.agent.ConversationReferenceIntent(
                        it.sourceSessionId,
                        it.sourceSessionTitle,
                        it.selectionKind.name,
                        it.sourceMessageIds,
                        it.content,
                        it.contentSha256,
                    )
                },
            ),
        control = control,
        wakeReason = wakeReason,
        expectedSessionModelId = expectedSessionModelId,
        history =
            when {
                spec.revisedMessageId != null -> {
                    com.helix.core.agent.turn.TurnHistoryMutation.Revise(
                        requireNotNull(spec.revisedMessageId),
                    )
                }

                spec.regenerateMessageId != null -> {
                    com.helix.core.agent.turn.TurnHistoryMutation.Regenerate(
                        requireNotNull(spec.regenerateMessageId),
                    )
                }

                else -> {
                    com.helix.core.agent.turn.TurnHistoryMutation.None
                }
            },
        recovery =
            spec.recoveryFromTurnId?.let {
                com.helix.core.agent.turn
                    .TurnRecoveryAdmission(it)
            },
        goal = goal,
    )

/** Explicit synthetic provider facts for tests of production admission preconditions. */
internal fun seedFixtureProvider(
    storage: HelixStorage,
    providerId: String,
    modelId: String,
) {
    storage.providerConfigs.save(
        com.helix.core.storage.repository.ProviderConfigSpec(
            id = providerId,
            displayName = "Synthetic admission provider",
            protocol = com.helix.core.model.ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://admission-fixture.invalid/v1",
            model = modelId,
            headersJson = "{}",
            secretAlias = "synthetic-unused",
            capabilitySnapshot = "{}",
        ),
    )
}
