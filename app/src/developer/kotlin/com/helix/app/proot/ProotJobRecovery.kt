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
        val queried =
            client.query(jobId) as? ProotJobClient.JobStateOutcome.Ok
                ?: return ProotRecoveryReport.Unknown
        val record = queried.record
        check(record.executionId == binding.getValue("executionId").jsonPrimitive.content)
        check(record.inputManifestSha256 == binding.getValue("inputManifestSha256").jsonPrimitive.content)
        return if (stop && !record.state.isTerminal) {
            client.cancel(jobId)
            ProotRecoveryReport(R.string.proot_recovery_stop_requested)
        } else {
            prootRecoveryReport(record)
        }
    }
}

internal fun prootRecoveryReport(record: com.helix.runtime.proot.ipc.ProotJobRecord): ProotRecoveryReport =
    when {
        record.evidenceExpired -> ProotRecoveryReport(R.string.proot_recovery_expired)
        !record.state.isTerminal -> ProotRecoveryReport(R.string.proot_recovery_running, canStop = true)
        record.state == ProotJobState.SUCCEEDED -> ProotRecoveryReport(R.string.proot_recovery_succeeded)
        record.state == ProotJobState.CANCELLED -> ProotRecoveryReport(R.string.proot_recovery_stopped)
        else -> ProotRecoveryReport(R.string.proot_recovery_ended)
    }
