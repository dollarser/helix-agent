package com.helix.app.provider

import com.helix.core.storage.HelixStorage
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import kotlinx.serialization.json.jsonPrimitive

/** Explicit original-identity query/stop. Never submits, imports output or reconciles away evidence. */
internal class SubscriptionJobRecovery(
    private val storage: HelixStorage,
    private val client: CliModelJobClient,
) {
    fun inspect(
        turnId: String,
        modelCallId: String,
        stop: Boolean,
    ): SubscriptionRecoveryStatus {
        check(storage.turns.resolve(turnId).state == "INTERRUPTED")
        val call = storage.modelCalls.resolve(modelCallId)
        check(call.turnId == turnId && call.state == "INTERRUPTED")
        val binding = SubscriptionJobBindingStore(storage).resolve(modelCallId)
        check(binding.getValue("turnId").jsonPrimitive.content == turnId)
        val job = binding.getValue("jobId").jsonPrimitive.content
        val hash = binding.getValue("requestSha256").jsonPrimitive.content
        val record = verified(client.query(job), job, hash) ?: return SubscriptionRecoveryStatus.UNKNOWN
        return if (stop && !record.state.terminal) {
            val stopped = verified(client.cancel(job), job, hash)
            when {
                stopped == null -> SubscriptionRecoveryStatus.UNKNOWN
                stopped.state.terminal -> status(stopped)
                else -> SubscriptionRecoveryStatus.STOP_REQUESTED
            }
        } else {
            status(record)
        }
    }

    private fun verified(
        outcome: CliModelJobClient.StateOutcome,
        job: String,
        hash: String,
    ): CliModelJobRecord? {
        val record = (outcome as? CliModelJobClient.StateOutcome.Ok)?.record ?: return null
        check(record.jobId == job && record.requestSha256 == hash)
        return record
    }

    private fun status(record: CliModelJobRecord): SubscriptionRecoveryStatus =
        when (record.state) {
            CliModelJobState.PENDING, CliModelJobState.RUNNING -> SubscriptionRecoveryStatus.RUNNING
            CliModelJobState.SUCCEEDED -> SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED
            CliModelJobState.EVIDENCE_EXPIRED -> SubscriptionRecoveryStatus.EVIDENCE_EXPIRED
            CliModelJobState.CANCELLED -> SubscriptionRecoveryStatus.STOPPED
            CliModelJobState.FAILED, CliModelJobState.INTERRUPTED -> SubscriptionRecoveryStatus.ENDED
        }
}
