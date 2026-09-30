package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Regression first reproduced against d9bac501. Uses the production cancellation registration seam. */
class PostCommitCancellationAuditTest {
    @Test fun cancellationBeforeBackendRegistrationMustPreventLateRequest() {
        val root = Files.createTempDirectory("post-commit-cancel").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val runner =
            CodexPayloadJobRunner(
                CodexPayloadJobStore(root),
                execute = { _, stop ->
                    // The production service decodes the request and starts foreground handling
                    // before registering the newly constructed model with this job's sticky stop token.
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    val closed = AtomicBoolean()
                    val backend = Closeable { closed.set(true) }
                    stop.using(backend) {
                        if (!closed.get()) requests.incrementAndGet()
                        CodexModelExecution("fixture", listOf(ModelEvent.Completed("stop")))
                    }
                },
                worker = worker,
            )
        try {
            val id = "job_abcdef000001"
            assertTrue(runner.submit(id, hash(), payload()) is CodexPayloadSubmit.Accepted)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(CliModelJobState.CANCEL_REQUESTED, runner.cancel(id)?.state)
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(CliModelJobState.CANCELLED, runner.query(id)?.state)
            assertEquals("A cancelled job must not start a backend request after late registration", 0, requests.get())
        } finally {
            release.countDown()
            runner.close()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test fun cancellationWhilePendingDoesPreventExecution() {
        val root = Files.createTempDirectory("post-commit-pending").toFile()
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        worker.submit { release.await(5, TimeUnit.SECONDS) }
        val requests = AtomicInteger()
        val runner =
            CodexPayloadJobRunner(CodexPayloadJobStore(root), { _, _ ->
                requests.incrementAndGet()
                CodexModelExecution("fixture", listOf(ModelEvent.Completed("stop")))
            }, worker = worker)
        try {
            val id = "job_abcdef000002"
            assertTrue(runner.submit(id, hash(), payload()) is CodexPayloadSubmit.Accepted)
            assertEquals(CliModelJobState.CANCELLED, runner.cancel(id)?.state)
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(0, requests.get())
        } finally {
            release.countDown()
            runner.close()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test fun backendCloseCanWaitForProgressWithoutHoldingTheRunnerLock() {
        val root = Files.createTempDirectory("cancel-close-lock").toFile()
        val entered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val queryFinished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(2)
        val runner =
            CodexPayloadJobRunner(CodexPayloadJobStore(root), { _, stop ->
                stop.using(
                    Closeable {
                        closeEntered.countDown()
                        check(queryFinished.await(3, TimeUnit.SECONDS))
                        release.countDown()
                    },
                ) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    CodexModelExecution("fixture", listOf(ModelEvent.Completed("stop")))
                }
            }, worker = worker)
        try {
            val id = "job_abcdef000003"
            runner.submit(id, hash(), payload())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val cancel = callers.submit<CliModelJobState?> { runner.cancel(id)?.state }
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            val query = callers.submit<CliModelJobState?> { runner.query(id)?.state }
            assertEquals(CliModelJobState.CANCEL_REQUESTED, query.get(2, TimeUnit.SECONDS))
            queryFinished.countDown()
            assertEquals(CliModelJobState.CANCEL_REQUESTED, cancel.get(2, TimeUnit.SECONDS))
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(CliModelJobState.CANCELLED, runner.query(id)?.state)
        } finally {
            queryFinished.countDown()
            release.countDown()
            runner.close()
            callers.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            callers.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    private fun payload() =
        CliModelRequestCodec.encode(
            ModelRequest("fixture", listOf(ModelMessage(ModelRole.USER, "synthetic audit fixture"))),
        )

    private fun hash() = MessageDigest.getInstance("SHA-256").digest(payload()).joinToString("") { "%02x".format(it) }
}
