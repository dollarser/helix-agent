package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CliResultAckQueueTest {
    private val result =
        CliModelJobRecord(
            "job_abcdef000001",
            "a".repeat(64),
            CliModelJobState.SUCCEEDED,
            1,
            2,
            "fixture",
            "b".repeat(64),
        )

    @Test fun lostReplySurvivesReopenAndRetryAcknowledgesOnlyOriginalResult() {
        val root = Files.createTempDirectory("ack-queue").toFile()
        try {
            val queue = CliResultAckQueue(root)
            queue.enqueue(result)
            var requests = 0
            assertFalse(
                queue.attempt(result.jobId) {
                    requests++
                    CliModelJobClient.StateOutcome.Unknown
                },
            )
            val reopened = CliResultAckQueue(root)
            assertEquals(listOf(result.jobId), reopened.pending())
            assertTrue(
                reopened.attempt(result.jobId) {
                    requests++
                    assertEquals(result, it)
                    CliModelJobClient.StateOutcome.Ok(it.copy(reconciledAtEpochMillis = 3))
                },
            )
            assertTrue(reopened.pending().isEmpty())
            assertTrue(reopened.attempt(result.jobId) { error("already acknowledged") })
            assertEquals(2, requests)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun validButMisnamedPendingRecordCannotAcknowledgeAnotherJob() {
        val root = Files.createTempDirectory("ack-foreign-file").toFile()
        try {
            val queue = CliResultAckQueue(root)
            queue.enqueue(result)
            val file = root.resolve("${result.jobId}.json")
            CliJobRecordFile().write(file, result.copy(jobId = "job_abcdef000002"))
            assertThrows(IllegalArgumentException::class.java) {
                queue.attempt(result.jobId) { error("must not acknowledge a foreign job") }
            }
            assertTrue(file.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun missingAckStampForeignOutputAndCorruptPendingNeverClearEvidence() {
        val root = Files.createTempDirectory("ack-truth").toFile()
        try {
            val queue = CliResultAckQueue(root)
            queue.enqueue(result)
            val wrong = listOf(result, result.copy(outputSha256 = "c".repeat(64), reconciledAtEpochMillis = 3))
            wrong.forEach { reply ->
                assertFalse(queue.attempt(result.jobId) { CliModelJobClient.StateOutcome.Ok(reply) })
            }
            assertThrows(
                IllegalArgumentException::class.java,
            ) { queue.enqueue(result.copy(outputSha256 = "d".repeat(64))) }
            root.resolve("${result.jobId}.json").writeText("{truncated")
            assertThrows(
                IllegalArgumentException::class.java,
            ) { queue.attempt(result.jobId) { error("must not send") } }
            assertEquals(listOf(result.jobId), queue.pending())
        } finally {
            root.deleteRecursively()
        }
    }
}
