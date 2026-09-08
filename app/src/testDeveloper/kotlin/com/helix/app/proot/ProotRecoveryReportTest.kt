package com.helix.app.proot

import com.helix.app.R
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProotRecoveryReportTest {
    @Test fun expiredSuccessDoesNotOfferARecoverableSuccessOrStop() {
        val record =
            ProotJobRecord(
                "job_000000000001",
                "exec_000000000001",
                "a".repeat(64),
                ProotJobState.SUCCEEDED,
                1,
                2,
                exitCode = 0,
                evidenceExpired = true,
            )
        val report = prootRecoveryReport(record)
        assertEquals(R.string.proot_recovery_expired, report.labelRes)
        assertFalse(report.canStop)
    }
}
