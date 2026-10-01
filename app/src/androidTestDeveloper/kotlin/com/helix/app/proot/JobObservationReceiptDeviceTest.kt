package com.helix.app.proot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.chat.JobObservationJournal
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.JobObservationEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Room receipt integration, without launching a shell or claiming reboot proof from a fixture. */
class JobObservationReceiptDeviceTest {
    private val binding = DetachedJobBinding("s", "t", "c", "job_aaaaaaaaaaaa", "exec-a", "a".repeat(64))
    private val running =
        ProotJobRecord(
            binding.jobId,
            binding.executionId,
            binding.inputManifestSha256,
            ProotJobState.RUNNING,
            1_000,
        )

    @Test fun lateRunningCannotOverrideACommittedTerminalObservation() =
        fixture { storage, receipts ->
            val terminal = running.copy(state = ProotJobState.SUCCEEDED, terminalAtEpochMs = 2_000, exitCode = 0)
            receipts.observe(binding, terminal)
            receipts.observe(binding, running)
            val last = latest(storage)
            assertTrue(last.terminal)
            assertEquals("SUCCEEDED", last.state)
            assertTrue(last.settlementPending)
            receipts.settled(binding, terminal)
            assertFalse(latest(storage).settlementPending)
        }

    @Test fun qualifiedDispositionRemainsUnknownAndRejectsLateReceipts() =
        fixture { storage, receipts ->
            receipts.observe(binding, running)
            // Test the existing trusted disposition sink, not the boot-count/reconciliation proof provider.
            receipts.disposed(binding, 2)
            receipts.observe(binding, running)
            val last = latest(storage)
            assertEquals("UNKNOWN", last.state)
            assertFalse(last.terminal)
            assertTrue(last.requiresReview)
            assertFalse(last.settlementPending)
            val terminal = running.copy(state = ProotJobState.SUCCEEDED, terminalAtEpochMs = 2_000, exitCode = 0)
            assertThrows(IllegalStateException::class.java) { receipts.settled(binding, terminal) }
            assertEquals("UNKNOWN", latest(storage).state)
        }

    private fun latest(storage: HelixStorage) =
        JobObservationEvidence.decode(
            Json
                .parseToJsonElement(
                    storage.auditEvents
                        .recentByCorrelation("s", JobObservationJournal.TYPE, 1)
                        .single()
                        .redactedPayload,
                ).jsonObject,
        )

    private fun fixture(block: (HelixStorage, DetachedJobObservationStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "job-receipt-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, files)
        try {
            block(storage, DetachedJobObservationStore(storage))
        } finally {
            storage.close()
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }
}
