package com.helix.app.proot

import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Synchronous transport can outlive its caller too. Reuse the host's durable execution identity. */
internal class ForegroundProotOwnership(
    private val ownership: ExecutionOwnership,
) {
    fun retain(
        callId: String,
        spec: ProotJobSpec,
    ) {
        check(ownership.retainForCall(callId, owner(callId, spec.executionId)))
    }

    fun notSubmitted(
        callId: String,
        spec: ProotJobSpec,
    ) {
        check(ownership.releaseUnsubmittedForCall(callId, owner(callId, spec.executionId)))
    }

    fun stopped(
        callId: String,
        spec: ProotJobSpec,
        record: ProotJobRecord,
    ) {
        require(record.jobId == spec.jobId && record.executionId == spec.executionId)
        require(record.inputManifestSha256 == spec.inputManifestSha256)
        if (settledRecord(record)) check(ownership.releaseStoppedForCall(callId, owner(callId, spec.executionId)))
    }

    fun recover(
        binding: JsonObject,
        currentBoot: Int?,
        query: (String) -> ProotJobClient.JobStateOutcome,
    ): Boolean {
        fun field(key: String) = binding.getValue(key).jsonPrimitive.content
        val call = field("toolCallId")
        val expected = owner(call, field("executionId"))
        val permit = ownership.acquireReconciliation(expected) ?: return false
        return permit.use {
            val originalBoot = binding["bootCount"]?.jsonPrimitive?.intOrNull
            val rebooted = originalBoot != null && currentBoot != null && currentBoot > originalBoot
            val record =
                if (rebooted) {
                    null
                } else {
                    verifiedProotRecoveryRecord(
                        query(field("jobId")),
                        field("jobId"),
                        field("executionId"),
                        field("inputManifestSha256"),
                    )
                }
            if (rebooted || record?.let(::settledRecord) == true) it.settle() else false
        }
    }

    companion object {
        const val PREFIX = "proot-sync:"

        fun owner(
            callId: String,
            executionId: String,
        ) = ExecutionOwnership.Owner(PREFIX + executionId, callId)

        fun settledRecord(record: ProotJobRecord): Boolean =
            record.state.isTerminal && record.state != ProotJobState.ORPHANED && !record.evidenceExpired
    }
}
