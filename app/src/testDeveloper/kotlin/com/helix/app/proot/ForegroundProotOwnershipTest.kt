package com.helix.app.proot

import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundProotOwnershipTest {
    private val record =
        ProotJobRecord("job_111111111111", "exec_111111111111", "a".repeat(64), ProotJobState.RUNNING, 1)

    private class Store : ExecutionOwnership.Store {
        var value: Set<ExecutionOwnership.Owner> = emptySet()

        override fun owners() = value

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (expected != value) return false
            value = replacement
            return true
        }
    }

    @Test fun restartAndOrphanNeverReleaseOnIntentOrSameBoot() {
        val store = Store()
        val old = ExecutionOwnership(store)
        val owner = ForegroundProotOwnership.owner("call", record.executionId)
        old.acquire("call")!!.use { assertTrue(it.retain(owner)) }
        val reopened = ExecutionOwnership(store)
        val lifecycle = ForegroundProotOwnership(reopened)
        val binding =
            buildJsonObject {
                put("toolCallId", "call")
                put("jobId", record.jobId)
                put("executionId", record.executionId)
                put("inputManifestSha256", record.inputManifestSha256)
                put("bootCount", 10)
            }
        assertFalse(lifecycle.recover(binding, 10) { ProotJobClient.JobStateOutcome.Ok(record) })
        requireNotNull(reopened.acquire("writer")).close()
        val orphan = record.copy(state = ProotJobState.ORPHANED, terminalAtEpochMs = 2)
        assertFalse(lifecycle.recover(binding, 10) { ProotJobClient.JobStateOutcome.Ok(orphan) })
        assertFalse(lifecycle.recover(binding, null) { ProotJobClient.JobStateOutcome.Unknown })
        assertEquals(owner, reopened.retainedOwners().singleOrNull())
        assertTrue(lifecycle.recover(binding, 11) { error("Real newer boot requires no IPC or replay") })
        assertNull(reopened.retainedOwners().singleOrNull())
    }

    @Test fun terminalEvidenceMustBeCurrentAndBoundToTheOriginalExecution() {
        val terminal = record.copy(state = ProotJobState.CANCELLED, terminalAtEpochMs = 2)
        assertTrue(ForegroundProotOwnership.settledRecord(terminal))
        assertFalse(ForegroundProotOwnership.settledRecord(terminal.copy(evidenceExpired = true)))
        assertFalse(ForegroundProotOwnership.settledRecord(record))
        val store = Store().apply { value = setOf(ForegroundProotOwnership.owner("call", record.executionId)) }
        val lifecycle = ForegroundProotOwnership(ExecutionOwnership(store))
        val binding =
            buildJsonObject {
                put("toolCallId", "call")
                put("jobId", record.jobId)
                put("executionId", record.executionId)
                put("inputManifestSha256", record.inputManifestSha256)
            }
        assertFalse(
            lifecycle.recover(binding, 20) {
                ProotJobClient.JobStateOutcome.Ok(terminal.copy(inputManifestSha256 = "b".repeat(64)))
            },
        )
        assertTrue(lifecycle.recover(binding, 20) { ProotJobClient.JobStateOutcome.Ok(terminal) })
    }
}
