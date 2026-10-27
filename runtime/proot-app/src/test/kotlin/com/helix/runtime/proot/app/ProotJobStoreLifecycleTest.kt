package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

class ProotJobStoreLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val day = 24L * 60 * 60 * 1000

    private fun record(
        index: Int,
        state: ProotJobState = ProotJobState.CANCELLED,
    ) = ProotJobRecord("job_" + index.toString(16).padStart(12, '0'), "exec_$index", "a".repeat(64), state, 1, 2)

    @Test fun expiredHistoryDoesNotBlockNewJobs() {
        val store = ProotJobStore(temporary.newFolder())
        repeat(128) { store.put(record(it).copy(evidenceExpired = true)) }
        assertTrue(store.pruneAndBudgetAvailable(100 * day))
        assertTrue(store.entries().isEmpty())
    }

    @Test fun recentReceiptsRemainDeduplicableWithoutOccupyingOutstandingBudget() {
        val store = ProotJobStore(temporary.newFolder())
        repeat(128) { store.put(record(it).copy(reconciledAtEpochMs = day)) }
        assertTrue(store.pruneAndBudgetAvailable(2 * day))
        assertEquals(128, store.entries().size)
    }

    @Test fun unreadResultsStillApplyBackpressure() {
        val store = ProotJobStore(temporary.newFolder())
        repeat(128) { store.put(record(it)) }
        assertFalse(store.pruneAndBudgetAvailable(day))
    }

    @Test fun orphanedAndPhysicallyRunningRecordsNeverExpire() {
        val store = ProotJobStore(temporary.newFolder(), physicallyRunning = { it == record(1).jobId })
        store.put(record(0, ProotJobState.ORPHANED))
        store.put(record(1))
        store.prune(100 * day)
        assertEquals(2, store.entries().size)
        assertFalse(store.load(record(1).jobId)!!.evidenceExpired)
    }

    @Test fun expiryPreservesOpenReadersAndAdjacentWorkspace() {
        val root = temporary.newFolder()
        val store = ProotJobStore(root)
        val record = record(0)
        store.put(record)
        store.outputFile(record.jobId).writeText("result")
        val workspace = File(root, "workspace").apply { mkdirs() }
        val history = File(workspace, ".ash_history").apply { writeText("keep") }
        store.readOutput { FileInputStream(store.outputFile(record.jobId)) }.use { reader ->
            store.prune(31 * day)
            assertEquals("result", reader.bufferedReader().readText())
        }
        assertTrue(store.load(record.jobId)!!.evidenceExpired)
        assertEquals("keep", history.readText())
        store.prune(38 * day)
        store.prune(38 * day)
        assertNull(store.load(record.jobId))
    }

    @Test fun backwardsClockDoesNotDiscardEvidence() {
        val store = ProotJobStore(temporary.newFolder())
        store.put(record(0).copy(terminalAtEpochMs = 100 * day))
        store.prune(day)
        assertFalse(store.load(record(0).jobId)!!.evidenceExpired)
    }

    @Test fun finishedScratchIsRemovedButResultsAndUnknownJobsRemain() {
        val store = ProotJobStore(temporary.newFolder())
        val ended = record(0)
        val unknown = record(1, ProotJobState.ORPHANED)
        listOf(ended, unknown).forEach {
            store.put(it)
            File(store.jobDir(it.jobId), "tmp").mkdirs()
            File(store.jobDir(it.jobId), "tmp/scratch").writeText("temporary")
            store.outputFile(it.jobId).writeText("result")
        }
        store.cleanupScratch(ended.jobId)
        store.cleanupScratch(unknown.jobId)
        assertFalse(File(store.jobDir(ended.jobId), "tmp").exists())
        assertTrue(File(store.jobDir(unknown.jobId), "tmp/scratch").exists())
        assertEquals("result", store.outputFile(ended.jobId).readText())
    }

    @Test fun restartedStoreKeepsUnreadResultAndExpiresOnlyAfterRetention() {
        val root = temporary.newFolder()
        ProotJobStore(root).put(record(0))
        val restarted = ProotJobStore(root)
        restarted.prune(day)
        assertFalse(restarted.load(record(0).jobId)!!.evidenceExpired)
        restarted.prune(31 * day)
        assertTrue(restarted.load(record(0).jobId)!!.evidenceExpired)
        assertTrue(restarted.pruneAndBudgetAvailable(31 * day))
    }

    @Test fun concurrentCleanupAndReceiptReadsDoNotLoseUncollectedResults() {
        val store = ProotJobStore(temporary.newFolder())
        repeat(64) { store.put(record(it)) }
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            List(4) {
                Thread {
                    try {
                        repeat(20) {
                            store.prune(day)
                            assertTrue(store.pruneAndBudgetAvailable(day))
                            assertEquals(64, store.entries().size)
                        }
                    } catch (error: Throwable) {
                        failures.add(error)
                    }
                }.apply { start() }
            }
        threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test fun failedCleanupKeepsEvidenceAndRetryCompletes() {
        var fail = true
        val failures = mutableListOf<Exception>()
        val store =
            ProotJobStore(
                temporary.newFolder(),
                cleanupFailure = { _, error -> failures.add(error) },
                remove = { file ->
                    !fail &&
                        com.helix.runtime.proot.core.RuntimeTreeDeletion
                            .remove(file)
                },
            )
        store.put(record(0))
        store.outputFile(record(0).jobId).writeText("result")
        store.prune(31 * day)
        assertFalse(store.load(record(0).jobId)!!.evidenceExpired)
        assertTrue(store.outputFile(record(0).jobId).exists())
        assertEquals(1, failures.size)
        fail = false
        store.prune(31 * day)
        assertTrue(store.load(record(0).jobId)!!.evidenceExpired)
        assertFalse(store.outputFile(record(0).jobId).exists())
    }

    @Test fun unknownAcknowledgementCannotDiscardPayload() {
        val store = ProotJobStore(temporary.newFolder())
        val unknown = record(0, ProotJobState.ORPHANED)
        store.put(unknown)
        store.outputFile(unknown.jobId).writeText("unresolved")
        assertNull(store.acknowledge(unknown.jobId, checkNotNull(unknown.terminalCommit), day))
        assertTrue(store.outputFile(unknown.jobId).exists())
    }

    @Test fun cancellationFailureAndTimeoutReleaseScratchWithoutDeletingProof() {
        val store = ProotJobStore(temporary.newFolder())
        listOf(ProotJobState.CANCELLED, ProotJobState.FAILED, ProotJobState.TIMED_OUT).forEachIndexed { index, state ->
            val record = record(index, state)
            store.put(record)
            File(store.jobDir(record.jobId), "tmp").mkdirs()
            store.cleanupScratch(record.jobId)
            assertFalse(File(store.jobDir(record.jobId), "tmp").exists())
            assertEquals(state, store.load(record.jobId)!!.state)
        }
    }

    @Test fun acknowledgementRetriesKeepOriginalCommitAndDoNotReplay() {
        val store = ProotJobStore(temporary.newFolder())
        val record = record(0)
        store.put(record)
        store.outputFile(record.jobId).writeText("result already persisted by host")
        assertEquals(
            record.terminalCommit,
            store.acknowledge(record.jobId, checkNotNull(record.terminalCommit), day)!!.terminalCommit,
        )
        assertEquals(
            record.terminalCommit,
            store.acknowledge(record.jobId, checkNotNull(record.terminalCommit), 2 * day)!!.terminalCommit,
        )
        assertEquals(day, store.load(record.jobId)!!.reconciledAtEpochMs)
        assertFalse(store.outputFile(record.jobId).exists())
    }

    @Test fun activeJobsRemainBudgetedEvenAfterLongDowntime() {
        val store = ProotJobStore(temporary.newFolder())
        repeat(128) { store.put(record(it).copy(state = ProotJobState.RUNNING, terminalAtEpochMs = null)) }
        assertFalse(store.pruneAndBudgetAvailable(100 * day))
        assertEquals(128, store.activeJobIds().size)
    }
}
