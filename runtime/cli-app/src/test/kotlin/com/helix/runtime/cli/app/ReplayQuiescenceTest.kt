package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import com.helix.runtime.cli.client.CliReplayMaintenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReplayQuiescenceTest {
    @Test fun liveWorkerPreventsMaintenanceEvenAfterCancellationRequest() {
        val root = Files.createTempDirectory("replay-live").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = CodexPayloadJobStore(root)
        val maintenance = AntigravityReplayMaintenance(java.io.File(root, "replay"))
        try {
            CodexPayloadJobRunner(store, { _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                CodexModelExecution("model", listOf(ModelEvent.Completed("stop")))
            }).use { runner ->
                val bytes =
                    CliModelRequestCodec.encode(
                        ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "hello"))),
                    )
                runner.submit("job_123456789012", CliReplayMaintenance.hash(bytes), bytes)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertTrue(runner.pruneReplay(emptyList(), maintenance).busy)
                runner.cancel("job_123456789012")
                assertTrue(runner.pruneReplay(emptyList(), maintenance).busy)
                release.countDown()
            }
        } finally {
            release.countDown()
            root.deleteRecursively()
        }
    }

    @Test fun unacknowledgedDurableResultBlocksAfterReopen() {
        val root = Files.createTempDirectory("replay-unacked").toFile()
        val store = CodexPayloadJobStore(root)
        try {
            val record =
                CliModelJobRecord(
                    "job_123456789013",
                    "a".repeat(64),
                    CliModelJobState.FAILED,
                    1,
                    terminalAtEpochMillis = 2,
                )
            store.put(record)
            CodexPayloadJobRunner(
                CodexPayloadJobStore(root),
                { _, _ -> error("must not execute") },
                clock = { 10L },
            ).use { runner ->
                val maintenance = AntigravityReplayMaintenance(java.io.File(root, "replay"))
                assertTrue(runner.pruneReplay(emptyList(), maintenance).busy)
                runner.finishReconcile(record)
                assertFalse(runner.pruneReplay(emptyList(), maintenance).busy)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
