package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CodexModelJobTest {
    private val hash = "a".repeat(64)

    @Test fun successIsDurableAndDuplicateNeverExecutesTwice() {
        val root = Files.createTempDirectory("codex-job").toFile()
        var calls = 0
        CodexModelJobRunner(CodexModelJobStore(root), { calls++; CodexSmokeResult("model", "HELIX_OK") }, {}).use { runner ->
            assertTrue(runner.submit("job_000000000001", hash) is CodexModelJobSubmit.Accepted)
            val terminal = await(runner, "job_000000000001")
            assertEquals(CodexModelJobState.SUCCEEDED, terminal.state)
            assertNotNull(terminal.outputSha256)
            assertTrue(runner.submit("job_000000000001", hash) is CodexModelJobSubmit.Duplicate)
            assertEquals(1, calls)
        }
    }

    @Test fun failureIsTerminalAndDoesNotPersistErrorText() {
        val root = Files.createTempDirectory("codex-job").toFile()
        CodexModelJobRunner(CodexModelJobStore(root), { error("secret server detail") }, {}).use { runner ->
            runner.submit("job_000000000002", hash)
            assertEquals(CodexModelJobState.FAILED, await(runner, "job_000000000002").state)
        }
        assertTrue(root.walkTopDown().filter { it.isFile }.none { it.readText().contains("secret server detail") })
    }

    @Test fun cancellationSettlesAndLateSuccessCannotOverwriteIt() {
        val root = Files.createTempDirectory("codex-job").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        CodexModelJobRunner(
            CodexModelJobStore(root),
            { entered.countDown(); release.await(); CodexSmokeResult("model", "HELIX_OK") },
            { cancelled.set(true); release.countDown() },
        ).use { runner ->
            runner.submit("job_000000000003", hash)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(CodexModelJobState.CANCELLED, runner.cancel("job_000000000003")?.state)
            assertTrue(cancelled.get())
            Thread.sleep(50)
            assertEquals(CodexModelJobState.CANCELLED, runner.query("job_000000000003")?.state)
        }
    }

    @Test fun restartParksNonTerminalRecordAsInterruptedWithoutExecution() {
        val root = Files.createTempDirectory("codex-job").toFile()
        val store = CodexModelJobStore(root)
        store.put(CodexModelJobRecord("job_000000000004", hash, CodexModelJobState.RUNNING, 10))
        var calls = 0
        CodexModelJobRunner(store, { calls++; CodexSmokeResult("model", "HELIX_OK") }, {}, { 20 }).use { runner ->
            val recovered = runner.query("job_000000000004")
            assertEquals(CodexModelJobState.INTERRUPTED, recovered?.state)
            assertEquals(0, calls)
            assertTrue(runner.submit("job_000000000004", hash) is CodexModelJobSubmit.Duplicate)
        }
    }

    @Test fun sameJobIdWithDifferentRequestIsRejected() {
        val root = Files.createTempDirectory("codex-job").toFile()
        CodexModelJobRunner(CodexModelJobStore(root), { CodexSmokeResult("model", "HELIX_OK") }, {}).use { runner ->
            runner.submit("job_000000000005", hash)
            assertEquals(CodexModelJobSubmit.RequestMismatch, runner.submit("job_000000000005", "b".repeat(64)))
        }
    }

    @Test fun secondJobIsRejectedUntilCancelledExecutionActuallyExits() {
        val root = Files.createTempDirectory("codex-job").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CodexModelJobRunner(
            CodexModelJobStore(root),
            { entered.countDown(); release.await(); CodexSmokeResult("model", "HELIX_OK") },
            {},
        ).use { runner ->
            runner.submit("job_000000000006", hash)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(CodexModelJobSubmit.Busy, runner.submit("job_000000000007", hash))
            assertEquals(CodexModelJobState.CANCELLED, runner.cancel("job_000000000006")?.state)
            assertEquals(CodexModelJobSubmit.Busy, runner.submit("job_000000000007", hash))
            release.countDown()
            repeat(100) {
                if (runner.submit("job_000000000007", hash) is CodexModelJobSubmit.Accepted) return@use
                Thread.sleep(10)
            }
            error("job lane did not reopen")
        }
    }

    @Test fun boundedJournalRejectsEntryBeyondCapWithoutEviction() {
        val root = Files.createTempDirectory("codex-job").toFile()
        val store = CodexModelJobStore(root)
        repeat(CodexModelJobStore.MAX_ENTRIES) { index ->
            store.put(
                CodexModelJobRecord(
                    "job_${index.toString(16).padStart(12, '0')}",
                    hash,
                    CodexModelJobState.INTERRUPTED,
                    1,
                    2,
                ),
            )
        }
        CodexModelJobRunner(store, { CodexSmokeResult("model", "HELIX_OK") }, {}).use { runner ->
            assertEquals(
                CodexModelJobSubmit.JournalFull,
                runner.submit("job_ffffffffffff", hash),
            )
        }
        assertEquals(CodexModelJobStore.MAX_ENTRIES, root.walkTopDown().count { it.name == "record.json" })
    }

    private fun await(runner: CodexModelJobRunner, jobId: String): CodexModelJobRecord {
        repeat(100) {
            runner.query(jobId)?.takeIf { it.state.terminal }?.let { return it }
            Thread.sleep(10)
        }
        error("job did not settle")
    }
}
