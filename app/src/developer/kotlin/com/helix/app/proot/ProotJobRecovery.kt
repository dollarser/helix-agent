package com.helix.app.proot

import com.helix.app.R
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.jsonPrimitive

/** User-triggered original-identity query/cancel; never submits or deletes result evidence. */
internal class ProotJobRecovery(
    private val storage: HelixStorage,
    private val client: ProotJobClient,
) {
    @Suppress("ReturnCount") // Unknown query, ordinary result and failed stop remain separate outcomes.
    fun inspect(
        turnId: String,
        callId: String,
        stop: Boolean,
    ): ProotRecoveryReport {
        val turn = storage.turns.resolve(turnId)
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(turnId, callId))
        check(prootRecoveryEligible(turn.state, call.state))
        check(call.name in setOf("bash", "code.linux.run"))
        val binding = ProotJobBindingStore(storage).resolve(callId)
        check(binding.getValue("turnId").jsonPrimitive.content == turnId)
        val jobId = binding.getValue("jobId").jsonPrimitive.content
        val execution = binding.getValue("executionId").jsonPrimitive.content
        val hash = binding.getValue("inputManifestSha256").jsonPrimitive.content
        val record =
            verifiedProotRecoveryRecord(client.query(jobId), jobId, execution, hash)
                ?: return ProotRecoveryReport.Unknown
        if (!stop || record.state.isTerminal) return prootRecoveryReport(record)
        val stopped =
            verifiedProotRecoveryRecord(client.cancel(jobId), jobId, execution, hash)
                ?: return ProotRecoveryReport.Unknown
        return if (stopped.state.isTerminal) {
            prootRecoveryReport(stopped)
        } else {
            ProotRecoveryReport(R.string.proot_recovery_stop_requested, status = ProotRecoveryStatus.RUNNING)
        }
    }
}

internal fun prootRecoveryReport(record: com.helix.runtime.proot.ipc.ProotJobRecord): ProotRecoveryReport =
    when {
        record.evidenceExpired -> {
            ProotRecoveryReport(
                R.string.proot_recovery_expired,
                status = ProotRecoveryStatus.EXPIRED,
            )
        }

        !record.state.isTerminal -> {
            ProotRecoveryReport(
                R.string.proot_recovery_running,
                canStop = true,
                status = ProotRecoveryStatus.RUNNING,
            )
        }

        record.state == ProotJobState.SUCCEEDED -> {
            ProotRecoveryReport(
                R.string.proot_recovery_succeeded,
                status = ProotRecoveryStatus.SUCCEEDED,
            )
        }

        record.state == ProotJobState.CANCELLED -> {
            ProotRecoveryReport(
                R.string.proot_recovery_stopped,
                status = ProotRecoveryStatus.TERMINAL,
            )
        }

        else -> {
            ProotRecoveryReport(R.string.proot_recovery_ended, status = ProotRecoveryStatus.TERMINAL)
        }
    }
