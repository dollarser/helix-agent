package com.helix.app.proot

import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.ipc.ProotJobRecord

/** Re-query and stop receipts must identify the exact original execution, not just any valid record. */
internal fun verifiedProotRecoveryRecord(
    outcome: ProotJobClient.JobStateOutcome,
    jobId: String,
    executionId: String,
    inputHash: String,
): ProotJobRecord? {
    val record = (outcome as? ProotJobClient.JobStateOutcome.Ok)?.record ?: return null
    return record.takeIf {
        it.jobId == jobId && it.executionId == executionId && it.inputManifestSha256 == inputHash
    }
}
