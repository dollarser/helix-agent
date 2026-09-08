package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CodexPayloadJobTest {
    @Test fun identicalModelAcrossPlatformsCannotReuseAJob() {
        val root = Files.createTempDirectory("subscription-platform").toFile()
        val model = ModelRequest("shared", listOf(ModelMessage(ModelRole.USER, "hello")))
        val claude = CliModelRequestCodec.encode(model, com.helix.runtime.cli.client.CliModelProvider.CLAUDE)
        val copilot = CliModelRequestCodec.encode(model, com.helix.runtime.cli.client.CliModelProvider.COPILOT)
        CodexPayloadJobRunner(CodexPayloadJobStore(root), {
            CodexModelExecution("shared", listOf(ModelEvent.Completed("stop")))
        }, {}).use {
            assertTrue(it.submit("job_144000000001", sha256(claude), claude) is CodexPayloadSubmit.Accepted)
            assertEquals(CodexPayloadSubmit.RequestMismatch, it.submit("job_144000000001", sha256(copilot), copilot))
        }
    }

    private val request =
        CliModelRequestCodec.encode(
            ModelRequest("model", listOf(ModelMessage(ModelRole.USER, "hello"))),
            com.helix.runtime.cli.client.CliModelProvider.CLAUDE,
        )
    private val hash = sha256(request)

    @Test fun outputPersistsUntilReconcileThenPayloadsAreDeleted() {
        val root = Files.createTempDirectory("codex-payload").toFile()
        val store = CodexPayloadJobStore(root)
        val events = listOf<ModelEvent>(ModelEvent.TextDelta("hello"), ModelEvent.Completed("stop"))
        CodexPayloadJobRunner(store, { CodexModelExecution("model", events) }, {}).use { runner ->
            assertTrue(runner.submit("job_134000000001", hash, request) is CodexPayloadSubmit.Accepted)
            val terminal = await(runner, "job_134000000001")
            assertEquals(CliModelJobState.SUCCEEDED, terminal.state)
            val prepared = runner.prepareReconcile(terminal.jobId)!!
            assertEquals(events, CliModelEventCodec.decode(prepared.payload!!))
            val reconciled = runner.finishReconcile(prepared.record)
            assertNotNull(reconciled.reconciledAtEpochMillis)
            assertEquals(null, runner.prepareReconcile(terminal.jobId)?.payload)
        }
        assertFalse(root.walkTopDown().any { it.name == "request.json" || it.name == "events.json" })
    }

    @Test fun hashMismatchDoesNotCreateARecord() {
        val root = Files.createTempDirectory("codex-payload").toFile()
        CodexPayloadJobRunner(CodexPayloadJobStore(root), { error("must not run") }, {}).use { runner ->
            assertThrows(IllegalArgumentException::class.java) {
                runner.submit("job_134000000002", "b".repeat(64), request)
            }
            assertEquals(null, runner.query("job_134000000002"))
        }
    }

    @Test fun cancelWinsOverLatePayload() {
        val root = Files.createTempDirectory("codex-payload").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CodexPayloadJobRunner(
            CodexPayloadJobStore(root),
            {
                entered.countDown()
                release.await()
                CodexModelExecution("model", listOf(ModelEvent.Completed("stop")))
            },
            { release.countDown() },
        ).use { runner ->
            runner.submit("job_134000000003", hash, request)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(CliModelJobState.CANCELLED, runner.cancel("job_134000000003")?.state)
            Thread.sleep(50)
            assertEquals(CliModelJobState.CANCELLED, runner.query("job_134000000003")?.state)
            assertEquals(null, runner.prepareReconcile("job_134000000003")?.payload)
        }
    }

    @Test fun payloadQuotaIncludesIncomingRequest() {
        val root = Files.createTempDirectory("codex-payload").toFile()
        val store = CodexPayloadJobStore(root)
        val payloadDir = root.resolve("provider-v1/codex-model-jobs/existing").apply { mkdirs() }
        payloadDir.resolve("events.json").writeBytes(ByteArray((CodexPayloadJobStore.MAX_PAYLOAD_BYTES - 1).toInt()))
        assertFalse(store.canAcceptNew(request.size))
    }

    @Test fun existingPayloadLayoutRemainsReadableAfterReopen() {
        val root = Files.createTempDirectory("codex-payload-reopen").toFile()
        val jobId = "job_134000000004"
        val output = CliModelEventCodec.encode(listOf(ModelEvent.Completed("stop")))
        val directory = root.resolve("provider-v1/codex-model-jobs/$jobId").apply { mkdirs() }
        directory.resolve("request.json").writeBytes(request)
        directory.resolve("events.json").writeBytes(output)
        val reopened = CodexPayloadJobStore(root)
        assertArrayEquals(request, reopened.loadRequest(jobId))
        assertArrayEquals(output, reopened.loadOutput(jobId))
        reopened.putRequest(jobId, request)
        reopened.putOutput(jobId, output)
        assertArrayEquals(request, directory.resolve("request.json").readBytes())
        assertArrayEquals(output, directory.resolve("events.json").readBytes())
    }

    @Test fun failedPayloadDeletionDoesNotMarkRecordReconciled() {
        val root = Files.createTempDirectory("codex-payload-delete").toFile()
        val store = CodexPayloadJobStore(root)
        val record =
            CliModelJobRecord("job_134000000005", hash, CliModelJobState.CANCELLED, 1L, terminalAtEpochMillis = 2L)
        store.put(record)
        val directory = root.resolve("provider-v1/codex-model-jobs/${record.jobId}")
        directory
            .resolve("request.json")
            .apply { mkdirs() }
            .resolve("keep")
            .writeText("occupied")
        val output = CliModelEventCodec.encode(listOf(ModelEvent.Completed("stop")))
        store.putOutput(record.jobId, output)
        assertThrows(IllegalArgumentException::class.java) { store.finishReconcile(record, 2L) }
        assertEquals(record, CodexPayloadJobStore(root).load(record.jobId))
        assertArrayEquals(output, store.loadOutput(record.jobId))
    }

    private fun await(
        runner: CodexPayloadJobRunner,
        jobId: String,
    ): com.helix.runtime.cli.client.CliModelJobRecord {
        repeat(100) {
            runner.query(jobId)?.takeIf { it.state.terminal }?.let { return it }
            Thread.sleep(10)
        }
        error("job did not settle")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
