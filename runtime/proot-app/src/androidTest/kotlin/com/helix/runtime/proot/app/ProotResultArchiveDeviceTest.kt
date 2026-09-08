package com.helix.runtime.proot.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.UUID

class ProotResultArchiveDeviceTest {
    @Test fun repeatedReadOnlyDescriptorsKeepTheOriginalUnacknowledgedResult() {
        withStore { store, record ->
            val bytes = "synthetic bounded archive bytes".toByteArray()
            store.put(record)
            store.outputFile(record.jobId).writeBytes(bytes)
            repeat(2) {
                fetchArchiveThroughBinder(store, record).use { archive ->
                    assertEquals(record, archive.record)
                    val duplicated = ParcelFileDescriptor.dup(archive.descriptor.fileDescriptor)
                    ParcelFileDescriptor.AutoCloseInputStream(duplicated).use { input ->
                        assertArrayEquals(bytes, input.readBytes())
                    }
                }
                assertEquals(record, store.load(record.jobId))
                assertArrayEquals(bytes, store.outputFile(record.jobId).readBytes())
            }
        }
    }

    @Test fun expiredAcknowledgedMissingAndRunningResultsCannotBeFetched() {
        withStore { store, record ->
            store.put(record)
            assertNull(ProotResultArchiveStore(store).open(record.jobId))
            store.outputFile(record.jobId).writeBytes(byteArrayOf(1))
            store.put(record.copy(evidenceExpired = true))
            assertNull(ProotResultArchiveStore(store).open(record.jobId))
            store.put(record.copy(reconciledAtEpochMs = System.currentTimeMillis()))
            assertNull(ProotResultArchiveStore(store).open(record.jobId))
            store.put(
                record.copy(
                    state = ProotJobState.RUNNING,
                    terminalAtEpochMs = null,
                    exitCode = null,
                    outputManifestSha256 = null,
                ),
            )
            assertNull(ProotResultArchiveStore(store).open(record.jobId))
        }
    }

    private fun withStore(block: (ProotJobStore, ProotJobRecord) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "result-fetch-${UUID.randomUUID()}")
        val now = System.currentTimeMillis()
        val record =
            ProotJobRecord(
                "job_000000000001",
                "fixture-result",
                "a".repeat(64),
                ProotJobState.SUCCEEDED,
                now - 1,
                now,
                exitCode = 0,
                outputManifestSha256 = "b".repeat(64),
            )
        try {
            block(ProotJobStore(root), record)
        } finally {
            root.deleteRecursively()
        }
    }
}
