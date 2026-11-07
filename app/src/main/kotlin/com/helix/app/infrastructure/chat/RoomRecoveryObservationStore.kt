package com.helix.app.infrastructure.chat

import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.recovery.OriginalRecoveryTargets
import com.helix.feature.conversation.application.recovery.RecoveryObservationStore
import com.helix.feature.conversation.application.recovery.SubscriptionRecoveryFact

/** Maps a coherent Room read into identities; runtime IO never occurs inside this transaction. */
internal class RoomRecoveryObservationStore(
    private val storage: HelixStorage,
) : RecoveryObservationStore {
    override fun originalTurn(turnId: String): OriginalRecoveryTargets {
        lateinit var result: OriginalRecoveryTargets
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            val prepared = preparedCalls(turn.sessionId)
            result =
                OriginalRecoveryTargets(
                    turnId = turn.id,
                    sessionId = turn.sessionId,
                    subscriptionModelCallIds =
                        if (turn.state == "INTERRUPTED") interruptedModelCalls(turn.id, prepared) else emptyList(),
                    unknownProotCallIds =
                        storage.toolCalls
                            .listByTurn(turn.id)
                            .filter {
                                it.name in setOf("bash", "code.linux.run", "skills.run") &&
                                    it.state in setOf("INTERRUPTED", "NEEDS_REVIEW")
                            }.map { it.callId },
                )
        }
        return result
    }

    override fun interruptedSubscriptions(sessionId: String): List<SubscriptionRecoveryFact> {
        var result: List<SubscriptionRecoveryFact> = emptyList()
        storage.withTransaction {
            val prepared = preparedCalls(sessionId)
            val artifactIds =
                storage.artifacts
                    .listBySession(sessionId)
                    .map { it.id }
                    .toSet()
            result =
                storage.turns.listBySession(sessionId).filter { it.state == "INTERRUPTED" }.flatMap { turn ->
                    interruptedModelCalls(turn.id, prepared).map { callId ->
                        SubscriptionRecoveryFact(turn.id, callId, "cli-result-$callId" in artifactIds)
                    }
                }
        }
        return result
    }

    private fun preparedCalls(sessionId: String): Set<String> =
        storage.auditEvents
            .listByCorrelation(sessionId)
            .filter { it.type == "cli.job_prepared" }
            .map { it.id }
            .toSet()

    private fun interruptedModelCalls(
        turnId: String,
        prepared: Set<String>,
    ): List<String> =
        storage.modelCalls
            .listByTurn(turnId)
            .filter {
                it.state == "INTERRUPTED" && "cli-job-${it.id}" in prepared
            }.map { it.id }
}
