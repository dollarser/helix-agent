package com.helix.runtime.proot.app

import com.helix.runtime.proot.core.DetachedLease
import com.helix.runtime.proot.core.JobAdmission
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DetachedJobStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancellationBeforeSubmissionSurvivesReopeningAndCannotBeDiscarded() {
        val root = temporary.newFolder()
        val journal = ProotJobStore(root)
        val store = DetachedJobStore(journal)
        val owner =
            DetachedJobStore.Record(
                DetachedJobBinding("session", "turn", "call", "job_abcdef123456", "exec_abcdef123456", "a".repeat(64)),
                DetachedLease("generation", 1_000, 100, 5_000),
                "b".repeat(64),
            )
        val gate = JobAdmission()
        val cancelled = gate.cancel { store.cancelUnsubmitted(owner) }
        assertNull(gate.start { error("cancelled command must not launch") })
        assertEquals(ProotJobState.CANCELLED, cancelled.state)
        assertNotNull(cancelled.terminalCommit)
        store.discardUnsubmitted(owner.binding.jobId)
        val reopened = ProotJobStore(root)
        assertEquals(cancelled, reopened.load(owner.binding.jobId))
        assertEquals(cancelled, DetachedJobStore(reopened).cancelUnsubmitted(owner))
    }
}
