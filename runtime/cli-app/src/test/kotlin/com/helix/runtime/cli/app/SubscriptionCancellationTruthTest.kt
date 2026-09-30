package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubscriptionCancellationTruthTest {
    @Test fun ignoredCancellationCannotBeAcknowledgedOrReleaseRunner() {
        val root = Files.createTempDirectory("cancel-truth").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val payload =
            CliModelRequestCodec.encode(
                ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "fixture"))),
            )
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val id = "job_ffffffff0001"
        val store = CodexPayloadJobStore(root)
        val runner =
            CodexPayloadJobRunner(store, {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                CodexModelExecution("model", listOf(ModelEvent.Completed("stop")))
            }, {})
        try {
            assertTrue(runner.submit(id, hash, payload) is CodexPayloadSubmit.Accepted)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val requested = requireNotNull(runner.cancel(id))
            assertEquals(CliModelJobState.CANCEL_REQUESTED, requested.state)
            assertFalse(requested.state.terminal)
            assertEquals(requested, CliModelJobRecordCodec.decode(CliModelJobRecordCodec.encode(requested)))
            assertNull(runner.acknowledgeResult(id, "$hash:"))
            assertEquals(CodexPayloadSubmit.Busy, runner.submit("job_ffffffff0002", hash, payload))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (runner.query(id)?.state?.terminal != true && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(CliModelJobState.CANCELLED, runner.query(id)?.state)
            assertNull(runner.prepareReconcile(id)?.payload)
            assertNotNull(runner.acknowledgeResult(id, "$hash:"))
        } finally {
            release.countDown()
            runner.close()
            root.deleteRecursively()
        }
    }
}
