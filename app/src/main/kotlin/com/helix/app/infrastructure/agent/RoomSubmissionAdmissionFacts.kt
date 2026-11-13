package com.helix.app.infrastructure.agent

import com.helix.app.agent.ContextHistory
import com.helix.app.application.agent.PendingSubmissionInput
import com.helix.app.application.agent.SubmissionAdmissionFacts
import com.helix.app.application.agent.SubmissionProviderSnapshot
import com.helix.app.application.agent.SubmissionProviderSnapshots
import com.helix.app.application.agent.SubmissionSession
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTurnSnapshot
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionInputState

/** Room projections retain revision/head/blocker checks; the Engine still owns atomic admission. */
internal class RoomSubmissionAdmissionFacts(
    private val storage: HelixStorage,
) : SubmissionAdmissionFacts {
    override fun isDelegatedTurn(turnId: String): Boolean = storage.sessionInputs.isDelegatedTurn(turnId)

    override fun session(sessionId: String): SubmissionSession? =
        storage.sessions.find(sessionId)?.let { SubmissionSession(it.id, it.providerId, it.modelId, it.archivedAt) }

    override fun retryText(
        sessionId: String,
        turnId: String,
    ): String? =
        storage.messages
            .listBySession(sessionId)
            .lastOrNull { it.turnId == turnId && it.role == "USER" }
            ?.let { ContextHistory.read(storage, it) }

    override fun pendingInput(
        inputId: String,
        expectedRevision: Long,
    ): PendingSubmissionInput? =
        storage.sessionInputs
            .get(inputId)
            ?.takeIf { it.revision == expectedRevision && it.state == SessionInputState.PENDING }
            ?.let { input ->
                PendingSubmissionInput(
                    input.inputId,
                    input.sessionId,
                    input.revision,
                    input.configuration,
                    storage.sessionInputs.readReference(input),
                    input.delegation,
                )
            }

    override fun hasQueuedInput(sessionId: String): Boolean = storage.sessionInputs.headQueue(sessionId) != null

    override fun queueStillConsumable(
        inputId: String,
        sessionId: String,
        expectedRevision: Long,
        requireHead: Boolean,
    ): Boolean {
        val current =
            storage.sessionInputs
                .get(inputId)
                ?.takeIf {
                    it.revision == expectedRevision && it.state == SessionInputState.PENDING &&
                        it.sessionId == sessionId
                }
                ?: return false
        val headMatches = !requireHead || storage.sessionInputs.headQueue(sessionId)?.inputId == current.inputId
        return headMatches && storage.turns.admissionBlockers(sessionId).isEmpty()
    }

    override fun markConfigurationChanged(
        input: PendingSubmissionInput,
        now: Long,
    ) {
        storage.sessionInputs.markNeedsAttention(input.inputId, input.revision, "INPUT_CONFIGURATION_CHANGED", now)
    }

    override fun recoverySessionId(turnId: String): String = storage.turns.resolve(turnId).sessionId

    override fun recoveryGoalId(turnId: String): String? =
        storage.goalTurnBindings.byTurn(turnId)?.let { storage.goalRuns.resolve(it.runId).goalId }
}

internal class ProviderSubmissionSnapshots(
    private val providers: ProviderService,
) : SubmissionProviderSnapshots {
    override suspend fun capture(
        providerId: String,
        modelId: String?,
    ): SubmissionProviderSnapshot {
        val config = providers.storedConfig(providerId)
        return SubmissionProviderSnapshot(ProviderTurnSnapshot.encode(config, modelId), modelId ?: config.model)
    }
}
