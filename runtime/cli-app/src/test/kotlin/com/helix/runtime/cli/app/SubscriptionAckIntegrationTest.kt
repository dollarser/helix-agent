package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import com.helix.runtime.cli.client.CliResultAckQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the real Runtime journal, payload cleanup, and host receipt queue together; no network. */
class SubscriptionAckIntegrationTest {
    @Test fun lostAcknowledgementReplyReopensWithoutRegenerationOrLosingLocalOutput() {
        val root = Files.createTempDirectory("ack-end-to-end").toFile()
        val worker = Executors.newSingleThreadExecutor()
        val events = listOf<ModelEvent>(ModelEvent.TextDelta("retained"), ModelEvent.Completed("stop"))
        var generations = 0
        try {
            CodexPayloadJobRunner(CodexPayloadJobStore(root.resolve("runtime")), { _, _ ->
                generations++
                CodexModelExecution("fixture", events)
            }, worker = worker).use { runner ->
                val payload =
                    CliModelRequestCodec.encode(
                        ModelRequest("fixture", listOf(ModelMessage(ModelRole.USER, "synthetic fixture"))),
                    )
                val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
                val id = "job_abcdef000011"
                assertTrue(runner.submit(id, hash, payload) is CodexPayloadSubmit.Accepted)
                worker.submit {}.get(5, TimeUnit.SECONDS)
                val record = requireNotNull(runner.query(id))
                assertEquals(CliModelJobState.SUCCEEDED, record.state)
                val local = root.resolve("verified-local-result.json")
                requireNotNull(runner.prepareReconcile(id)?.payload).use { local.writeBytes(it.readBytes()) }
                assertEquals(events, CliModelEventCodec.decode(local.readBytes()))
                val queue = CliResultAckQueue(root.resolve("receipts"))
                queue.enqueue(record)
                assertFalse(
                    queue.attempt(id) {
                        assertNotNull(runner.acknowledgeResult(id, "${it.requestSha256}:${it.outputSha256}"))
                        CliModelJobClient.StateOutcome.Unknown // The server committed; its reply was lost.
                    },
                )
                assertNull(runner.prepareReconcile(id)?.payload)
                val reopened = CliResultAckQueue(root.resolve("receipts"))
                assertEquals(listOf(id), reopened.pending())
                assertTrue(
                    reopened.attempt(id) {
                        CliModelJobClient.StateOutcome.Ok(
                            requireNotNull(runner.acknowledgeResult(id, "${it.requestSha256}:${it.outputSha256}")),
                        )
                    },
                )
                assertTrue(reopened.pending().isEmpty())
                assertEquals(events, CliModelEventCodec.decode(local.readBytes()))
                assertEquals(1, generations)
            }
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test fun acknowledgedQueueFreesFullRuntimeJournalWithoutExecutingAnything() {
        val root = Files.createTempDirectory("ack-full-journal").toFile()
        val worker = Executors.newSingleThreadExecutor()
        val store = CodexPayloadJobStore(root.resolve("runtime"))
        val queue = CliResultAckQueue(root.resolve("receipts"))
        val now = System.currentTimeMillis()
        try {
            repeat(CodexModelJobStore.MAX_ENTRIES) { index ->
                val record =
                    CliModelJobRecord(
                        "job_" + index.toString(16).padStart(12, '0'),
                        "a".repeat(64),
                        CliModelJobState.CANCELLED,
                        now,
                        now,
                    )
                store.put(record)
                queue.enqueue(record)
            }
            assertFalse(store.canAcceptNew(1))
            val execute = { _: ByteArray, _: SubscriptionCancellation -> error("ACK must not execute a model") }
            CodexPayloadJobRunner(store, execute, worker = worker).use { runner ->
                queue.pending().forEach { id ->
                    assertTrue(
                        queue.attempt(id) {
                            CliModelJobClient.StateOutcome.Ok(
                                requireNotNull(runner.acknowledgeResult(id, "${it.requestSha256}:")),
                            )
                        },
                    )
                }
                assertTrue(queue.pending().isEmpty())
                assertTrue(store.canAcceptNew(1))
            }
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}
