package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliModelJobRecordCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CodexEvidenceExpiryTest {
    @Test fun expiredResultLeavesDurableIdentityWithoutPayloadOrSuccessProof() {
        val root = Files.createTempDirectory("cli-evidence-expiry").toFile()
        try {
            val store = CodexPayloadJobStore(root)
            val record =
                CodexModelJobRecord(
                    "job_000000000001",
                    "a".repeat(64),
                    CodexModelJobState.SUCCEEDED,
                    1,
                    2,
                    "fixture",
                    "b".repeat(64),
                )
            store.put(record)
            store.putRequest(record.jobId, byteArrayOf(1))
            store.putOutput(record.jobId, byteArrayOf(2))
            store.recoverInterrupted(2 + THIRTY_DAYS)
            val marker = requireNotNull(store.load(record.jobId))
            assertEquals("EVIDENCE_EXPIRED", marker.state.name)
            assertEquals(record.requestSha256, marker.requestSha256)
            assertNull(marker.outputSha256)
            assertNull(marker.model)
            assertNull(marker.reconciledAtEpochMillis)
            assertNull(store.loadOutput(record.jobId))
            assertEquals(marker, CliModelJobRecordCodec.decode(CliModelJobRecordCodec.encode(marker)))
            assertEquals(marker, CodexPayloadJobStore(root).load(record.jobId))
            var calls = 0
            CodexPayloadJobRunner(store, {
                calls++
                error("must not execute")
            }, {}).use { runner ->
                assertEquals(marker, runner.query(record.jobId))
                assertTrue(
                    runner.submit(record.jobId, record.requestSha256, byteArrayOf(1))
                        is CodexPayloadSubmit.Duplicate,
                )
                assertNull(runner.prepareReconcile(record.jobId)?.payload)
                assertEquals(marker, runner.finishReconcile(marker))
                assertEquals(0, calls)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun boundaryAndClockRollbackPreserveUnexpiredResult() {
        val root = Files.createTempDirectory("cli-evidence-boundary").toFile()
        try {
            val store = CodexPayloadJobStore(root)
            val record =
                CodexModelJobRecord(
                    "job_000000000002",
                    "a".repeat(64),
                    CodexModelJobState.CANCELLED,
                    1,
                    100,
                )
            store.put(record)
            store.putRequest(record.jobId, byteArrayOf(1))
            store.recoverInterrupted(99)
            assertEquals(record, store.load(record.jobId))
            store.recoverInterrupted(99 + THIRTY_DAYS)
            assertEquals(record, store.load(record.jobId))
            assertArrayEquals(byteArrayOf(1), store.loadRequest(record.jobId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cleanupFailureRetainsMarkerAndNextRecoveryRetriesDeletion() {
        val root = Files.createTempDirectory("cli-evidence-delete").toFile()
        try {
            val store = CodexPayloadJobStore(root)
            val record =
                CodexModelJobRecord(
                    "job_000000000003",
                    "a".repeat(64),
                    CodexModelJobState.CANCELLED,
                    1,
                    2,
                )
            store.put(record)
            val blocked = root.resolve("provider-v1/codex-model-jobs/${record.jobId}/request.json")
            blocked.mkdirs()
            blocked.resolve("occupied").writeText("fixture")
            store.putOutput(record.jobId, byteArrayOf(2))
            assertThrows(IllegalArgumentException::class.java) { store.recoverInterrupted(2 + THIRTY_DAYS) }
            val marker = requireNotNull(store.load(record.jobId))
            assertEquals("EVIDENCE_EXPIRED", marker.state.name)
            blocked.deleteRecursively()
            store.recoverInterrupted(3 + THIRTY_DAYS)
            assertNull(store.loadOutput(record.jobId))
            assertEquals(marker, store.load(record.jobId))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000
    }
}
