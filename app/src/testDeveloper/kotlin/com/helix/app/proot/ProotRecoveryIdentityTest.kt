package com.helix.app.proot

import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProotRecoveryIdentityTest {
    private val record =
        ProotJobRecord("job_000000000001", "exec_000000000001", "a".repeat(64), ProotJobState.RUNNING, 1)

    @Test fun queryAndCancellationRejectEveryForeignIdentity() {
        fun verify(value: ProotJobRecord) =
            verifiedProotRecoveryRecord(
                ProotJobClient.JobStateOutcome.Ok(value),
                record.jobId,
                record.executionId,
                record.inputManifestSha256,
            )
        assertEquals(record, verify(record))
        assertNull(verify(record.copy(jobId = "job_000000000002")))
        assertNull(verify(record.copy(executionId = "exec_000000000002")))
        assertNull(verify(record.copy(inputManifestSha256 = "b".repeat(64))))
    }

    @Test fun failedStopIsUnknownNotStopRequestedOrStopped() {
        listOf(
            ProotJobClient.JobStateOutcome.Unknown,
            ProotJobClient.JobStateOutcome.Refused(UnavailableCause.DEAD_OBJECT),
        ).forEach {
            assertNull(verifiedProotRecoveryRecord(it, record.jobId, record.executionId, record.inputManifestSha256))
        }
    }
}
