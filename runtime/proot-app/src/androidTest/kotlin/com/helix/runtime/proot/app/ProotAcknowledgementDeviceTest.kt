package com.helix.runtime.proot.app

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ProotAcknowledgementDeviceTest {
    @Test fun exactAcknowledgementRejectsWrongProofAndPreservesFirstReceipt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "ack-identity-${UUID.randomUUID()}")
        val store = ProotJobStore(root)
        val now = System.currentTimeMillis()
        val record =
            ProotJobRecord(
                "job_000000000002",
                "ack-fixture",
                "a".repeat(64),
                ProotJobState.SUCCEEDED,
                now - 1,
                now,
                exitCode = 0,
                outputManifestSha256 = "b".repeat(64),
            )
        try {
            store.put(record)
            store.outputFile(record.jobId).writeText("fixture archive")
            assertNull(store.acknowledge(record.jobId, "0".repeat(64), now + 1))
            assertTrue(store.outputFile(record.jobId).isFile)
            assertEquals(record, store.load(record.jobId))
            val proof = requireNotNull(record.terminalCommit)
            val receipt = requireNotNull(store.acknowledge(record.jobId, proof, now + 2))
            assertEquals(now + 2, receipt.reconciledAtEpochMs)
            assertFalse(store.outputFile(record.jobId).exists())
            assertEquals(receipt, store.acknowledge(record.jobId, proof, now + 3))
            assertNull(ProotResultArchiveStore(store).open(record.jobId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun failedPayloadDeletionCannotPublishAReconciliationReceipt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "ack-failure-${UUID.randomUUID()}")
        val store = ProotJobStore(root)
        val now = System.currentTimeMillis()
        val record =
            ProotJobRecord(
                "job_000000000001",
                "ack-fixture",
                "a".repeat(64),
                ProotJobState.SUCCEEDED,
                now - 1,
                now,
                exitCode = 0,
                outputManifestSha256 = "b".repeat(64),
            )
        store.put(record)
        val protected = File(store.jobDir(record.jobId), "protected").apply { mkdirs() }
        File(protected, "payload").writeText("fixture")
        Os.chmod(protected.path, 0b101000000)
        try {
            assertThrows(IllegalStateException::class.java) { store.reconcile(record.jobId, now + 1) }
            assertEquals(record, store.load(record.jobId))
        } finally {
            Os.chmod(protected.path, 0b111000000)
            root.deleteRecursively()
        }
    }
}
