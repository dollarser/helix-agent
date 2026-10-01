package com.helix.app.agent

import com.helix.core.agent.AgentTurnJournal
import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.TurnMessageDraft

/**
 * One adapter over the existing transaction owner; it holds no second runtime or stored facts.
 * Mirrors one semantic journal; extra delegates would not split transaction ownership.
 */
@Suppress("TooManyFunctions")
internal class RoomTurnJournal(
    private val coordinator: TurnCoordinator,
) : AgentTurnJournal {
    override val id: String get() = coordinator.id

    override fun snapshot() = coordinator.snapshot()

    override fun currentStream() = coordinator.currentStream()

    override suspend fun beginModelStream(compacting: Boolean) = coordinator.beginModelStream(compacting)

    override suspend fun retryEmptyModelStream(nextModelCallId: String) =
        coordinator.retryEmptyModelStream(nextModelCallId)

    override suspend fun recordDiagnostic(
        kind: String,
        body: String,
    ) = coordinator.recordDiagnostic(kind, body)

    override suspend fun recordPromptSnapshot(
        prompt: PromptSnapshot?,
        compacting: Boolean,
    ) = coordinator.recordPromptSnapshot(prompt, compacting)

    override suspend fun recordRequestManifest(manifestJson: String?) = coordinator.recordRequestManifest(manifestJson)

    override suspend fun recordInputRequestStarted(messageIds: Set<String>) =
        coordinator.recordInputRequestStarted(messageIds)

    override suspend fun beginToolBatch(callIds: List<String>) = coordinator.beginToolBatch(callIds)

    override suspend fun commitModelToolStep(toolCallsJson: String) = coordinator.commitModelToolStep(toolCallsJson)

    override fun settleBatchCall(
        callId: String,
        sideEffectUnknown: Boolean,
    ) = coordinator.settleBatchCall(callId, sideEffectUnknown)

    override suspend fun openNextModelCall(
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ) = coordinator.openNextModelCall(messages, nextModelCallId)

    override suspend fun commitCompaction(
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String?,
        failureReason: String?,
        saveSummary: Boolean,
    ) = coordinator.commitCompaction(plan, nextModelCallId, notice, failureReason, saveSummary)
}
