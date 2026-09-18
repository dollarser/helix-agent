@file:Suppress("LongMethod") // End-to-end assertions keep each real job journey in one test.

package com.helix.app.proot

import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.SystemClock
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotLogClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.JobLogPage
import com.helix.runtime.proot.core.JobLogText
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotLogWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProotLogStreamDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val storage get() = context.appContainer.storage
    private val jobs = mutableListOf<Job>()
    private val supervisor by lazy { ProotRuntimeSupervisor(context) }
    private val client by lazy { ProotJobClient(supervisor) }
    private val logs = ProotLogClient()

    @Before fun prepare() {
        ensureInstalledRuntime(context)
    }

    @After fun cleanup() {
        jobs.forEach { client.cancel(it.id) }
        supervisor.closeConnection()
    }

    @Test fun readsBothStreamsBeforeExitAndMatchesVerifiedFinalArchive() {
        val job = start("""printf '\344'; sleep 1; printf '\270\255'; printf 'ERR' >&2; sleep 3; printf 'TAIL'""")
        val text = JobLogText()
        val first = awaitPage(job, null)
        assertFalse(first.eof)
        assertFalse((client.query(job.id) as ProotJobClient.JobStateOutcome.Ok).record.state.isTerminal)
        text.append(first)
        var cursor: String? = first.cursor
        assertEquals(first.cursor, logs.read(job.id, job.hash, null)!!.cursor)
        val terminal = client.awaitTerminal(job.id, timeoutMs = 30000) as ProotJobClient.AwaitOutcome.Terminal
        val logDeadline = System.nanoTime() + 10_000_000_000L
        do {
            assertTrue("Log EOF deadline", System.nanoTime() < logDeadline)
            val page = awaitPage(job, cursor, allowEmpty = true)
            text.append(page)
            cursor = page.cursor
            Thread.sleep(25)
        } while (!page.eof)
        assertEquals("中TAIL", text.stdout)
        assertEquals("ERR", text.stderr)
        assertFalse(text.truncated)
        val extracted = File(job.output.parentFile, "extracted")
        val proof = ZipJobExtractor.extract(job.output, extracted)
        assertEquals(terminal.record.outputManifestSha256, proof.manifestSha256)
        assertEquals(text.stdout, File(extracted, "stdout.txt").readText())
        assertEquals(text.stderr, File(extracted, "stderr.txt").readText())
        assertNull(terminal.record.reconciledAtEpochMs)
    }

    @Test fun realCommandIsVisibleOnTheDetailPageBeforeItEndsWithoutModelCalls() {
        compose.resetDeterministicUiState()
        val job = start("printf 'LIVE_BEFORE_EXIT'; sleep 60")
        val session = "log-session-${job.id}"
        val turn = "log-turn-${job.id}"
        val call = "log-call-${job.id}"
        storage.sessions.create(session, "Log journey", null, null, System.currentTimeMillis())
        TurnCoordinator.start(storage, SystemClock(), {
            UUID.randomUUID().toString()
        }, TurnStartSpec(session, turn, "$turn-model", "fixture", "log fixture"))
        storage.toolCalls.append(
            call,
            turn,
            call,
            "bash",
            "1",
            """{"command":["printf LIVE_BEFORE_EXIT"]}""",
            "RUNNING",
        )
        storage.auditEvents.append(
            "proot-job-$call",
            session,
            "proot.job_prepared",
            "platform",
            """{"version":1,"toolCallId":"$call","turnId":"$turn","jobId":"${job.id}",""" +
                """"executionId":"${job.execution}","inputManifestSha256":"${job.hash}"}""",
            System.currentTimeMillis(),
        )
        val modelCalls = storage.modelCalls.listByTurn(turn).size
        compose.navigateTo("tasks")
        compose.waitUntil(20000) { compose.onAllNodesWithTag("tasks-turn-$turn").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tasks-turn-command-$turn").performScrollTo().performClick()
        compose.waitUntil(
            20000,
        ) { compose.onAllNodesWithTag("turn-command-detail-$call").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("turn-command-detail-$call").performScrollTo().performClick()
        compose.waitUntil(
            20000,
        ) { compose.onAllNodesWithTag("command-detail-stdout").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("command-detail-stdout").assertTextContains("LIVE_BEFORE_EXIT")
        assertFalse((client.query(job.id) as ProotJobClient.JobStateOutcome.Ok).record.state.isTerminal)
        assertEquals(modelCalls, storage.modelCalls.listByTurn(turn).size)
        assertNull(storage.toolResults.byToolCall(call))
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitUntil(
            20000,
        ) { compose.onAllNodesWithTag("command-detail-stdout").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("command-detail-stdout").assertTextContains("LIVE_BEFORE_EXIT")
        assertEquals(modelCalls, storage.modelCalls.listByTurn(turn).size)
        assertNull(storage.toolResults.byToolCall(call))
    }

    @Test fun cancellationKeepsTailAndRejectsCrossJobOrWrongBinding() {
        val job = start("printf 'TAIL_BEFORE_CANCEL'; sleep 60")
        val page = awaitPage(job, null)
        assertNull(logs.read(job.id, "f".repeat(64), null))
        client.cancel(job.id)
        val terminal = client.awaitTerminal(job.id, timeoutMs = 30000) as ProotJobClient.AwaitOutcome.Terminal
        assertEquals("CANCELLED", terminal.record.state.name)
        var tail: JobLogPage
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            assertTrue("Cancel EOF deadline", System.nanoTime() < deadline)
            tail = awaitPage(job, page.cursor, allowEmpty = true)
            Thread.sleep(25)
        } while (!tail.eof)
        val next = start("echo NEXT")
        awaitPage(next, null)
        assertNull(logs.read(next.id, next.hash, page.cursor))
        assertEquals("TAIL_BEFORE_CANCEL", page.bytes.decodeToString())
    }

    @Test fun binderDeathMakesPreviewUnavailableWithoutRebindingOrReplay() {
        val job = start("printf 'BEFORE_DEATH'; sleep 60")
        val page = awaitPage(job, null)
        val connection = supervisor.openConnection() as ProotConnection.Opened
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            runCatching { connection.binder.transact(ProotRuntimeProtocol.TX_DEBUG_SELF_KILL, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
            supervisor.closeConnection()
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (connection.binder.isBinderAlive && System.nanoTime() < deadline) Thread.sleep(25)
        assertFalse(connection.binder.isBinderAlive)
        repeat(3) { assertNull(logs.read(job.id, job.hash, page.cursor)) }
        // Reading never obtains another Binder. Only explicit query below may cold-bind
        // for reconciliation, which must report ORPHANED rather than rerun the command.
        val record = client.awaitTerminal(job.id, timeoutMs = 30000) as ProotJobClient.AwaitOutcome.Terminal
        assertEquals("ORPHANED", record.record.state.name)
    }

    @Test fun slowReaderAndBothStreamPressureDoNotTruncateTheVerifiedArchive() {
        val job = start("printf '%3145728s' x; printf '%3145728s' y >&2", 8 * 1024 * 1024)
        // Do not read preview while the real process drains six MiB into its final archive.
        val terminal = client.awaitTerminal(job.id, timeoutMs = 60000) as ProotJobClient.AwaitOutcome.Terminal
        val extracted = File(job.output.parentFile, "pressure-result")
        val proof = ZipJobExtractor.extract(job.output, extracted)
        val diagnostic = "${terminal.record}; stderr tail=${File(extracted, "stderr.txt").readText().takeLast(512)}"
        assertEquals(diagnostic, "SUCCEEDED", terminal.record.state.name)
        var cursor: String? = null
        var bytes = 0
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            assertTrue("Pressure EOF deadline", System.nanoTime() < deadline)
            val page = awaitPage(job, cursor, allowEmpty = true)
            assertTrue(page.truncated)
            bytes += page.bytes.size
            cursor = page.cursor
        } while (!page.eof)
        assertTrue(bytes in 1..(4 * 1024 * 1024))
        assertEquals(terminal.record.outputManifestSha256, proof.manifestSha256)
        assertEquals(3145728L, File(extracted, "stdout.txt").length())
        assertEquals(3145728L, File(extracted, "stderr.txt").length())
    }

    @Test fun wireRejectsUnsupportedVersionsOversizedPagesAndTrailingData() {
        fun parcel(check: (Parcel) -> Unit) {
            val reply = Parcel.obtain()
            try {
                check(reply)
            } finally {
                reply.recycle()
            }
        }
        parcel { reply ->
            ProotLogWire.write(reply, JobLogPage("job:generation:0", 1, ByteArray(8192), true, true))
            assertTrue(reply.dataSize() <= ProotLogWire.MAX_REPLY_BYTES)
            reply.setDataPosition(0)
            assertEquals(8192, ProotLogWire.read(reply)!!.bytes.size)
        }
        parcel { reply ->
            reply.writeNoException()
            reply.writeByte(ProotRuntimeProtocol.REPLY_JOB_STATE)
            reply.writeInt(999)
            reply.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { ProotLogWire.read(reply) }
        }
        parcel { reply ->
            ProotLogWire.write(reply, JobLogPage("", 1, ByteArray(33000), false, false))
            reply.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { ProotLogWire.read(reply) }
        }
        parcel { reply ->
            ProotLogWire.write(reply, JobLogPage("", 1, byteArrayOf(), true, false))
            reply.writeInt(7)
            reply.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { ProotLogWire.read(reply) }
        }
    }

    private fun start(
        command: String,
        maxOutputBytes: Int = 1048576,
    ): Job {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val dir = File(context.cacheDir, "log-test-$suffix").apply { mkdirs() }
        val manifest = JobManifestCodec.encode(JobManifest(emptyList()))
        val hash =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(manifest.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val input = File(dir, "input.zip")
        JobZipWriter(input.outputStream()).use { it.writeManifest(manifest) }
        val job = Job("job_$suffix", "exec_$suffix", hash, File(dir, "output.zip"))
        val spec =
            ProotJobSpec(
                job.execution,
                job.id,
                ProotJobCommand.Argv(listOf("/bin/sh", "-c", command)),
                "",
                emptyMap(),
                90000,
                maxOutputBytes.toLong(),
                hash,
            )
        val source = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        val target =
            ParcelFileDescriptor.open(
                job.output,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        assertTrue(client.submit(spec, source, target) is ProotJobClient.SubmitOutcome.Accepted)
        jobs.add(job)
        return job
    }

    private fun awaitPage(
        job: Job,
        cursor: String?,
        allowEmpty: Boolean = false,
    ): JobLogPage {
        val deadline = System.nanoTime() + 20_000_000_000L
        var page: JobLogPage?
        do {
            page = logs.read(job.id, job.hash, cursor)
            if (page != null && (allowEmpty || page.bytes.isNotEmpty())) return page
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        assertNotNull("No log page from approved job", page)
        error("Expected log bytes")
    }

    private data class Job(
        val id: String,
        val execution: String,
        val hash: String,
        val output: File,
    )
}
