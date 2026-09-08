package com.helix.app.proot

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Production cross-UID PRoot client and guest shell; no ChatService/Goal binding claim. */
class ProotOwnerProcessKillDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory get() = File(context.filesDir, "proot-owner-kill")
    private val marker get() = File(directory, "record.json")
    private val client get() = ProotJobClient(ProotRuntimeSupervisor(context))

    @Test fun originalGuestJobIsReconciledAfterMainProcessDeath() {
        val phase = InstrumentationRegistry.getArguments().getString("proot.owner.phase")
        assumeTrue("Requires the dedicated host SIGKILL runner", phase != null)
        when (phase) {
            "prepare" -> prepare()
            "recover", "recover-final" -> recover(phase == "recover-final")
            else -> error("Unexpected host phase")
        }
    }

    private fun prepare() {
        check(!directory.exists()) { "Inspect the existing owned PRoot fixture first" }
        check(directory.mkdir())
        val id =
            "job_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val document = JobManifestCodec.encode(JobManifest(emptyList()))
        val archive = File(directory, "input.zip")
        JobZipWriter(archive.outputStream()).use { it.writeManifest(document) }
        val hash =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(document.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val spec =
            ProotJobSpec(
                executionId = "exec-owner-$id",
                jobId = id,
                command =
                    ProotJobCommand.Argv(
                        listOf("/bin/sh", "-c", "echo PROOT_OWNER_STARTED > /workspace/started.txt; /bin/sleep 20"),
                    ),
                relativeWorkingDirectory = "",
                environment = emptyMap(),
                deadlineMs = 30000L,
                maxOutputBytes = 1048576L,
                inputManifestSha256 = hash,
            )
        ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor
                .open(
                    File(directory, "output.zip"),
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { output ->
                    val accepted = client.submit(spec, input, output) as ProotJobClient.SubmitOutcome.Accepted
                    marker.writeText(ProotJobRecordCodec.encode(accepted.record))
                }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while ((client.query(id) as ProotJobClient.JobStateOutcome.Ok).record.state != ProotJobState.RUNNING) {
            assertTrue("PRoot Job did not reach RUNNING", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "PROOT_OWNER_KILL_READY pid=${android.os.Process.myPid()} job=$id\n")
            },
        )
        Thread.sleep(15000)
        error("Host did not kill the PRoot client")
    }

    private fun recover(final: Boolean) {
        val original = ProotJobRecordCodec.parse(marker.readText())
        val observed = (client.query(original.jobId) as ProotJobClient.JobStateOutcome.Ok).record
        assertEquals(original.executionId, observed.executionId)
        assertEquals(original.inputManifestSha256, observed.inputManifestSha256)
        assertEquals(original.createdAtEpochMs, observed.createdAtEpochMs)
        if (!observed.state.isTerminal) client.cancel(original.jobId)
        val terminal =
            (
                client.awaitTerminal(
                    original.jobId,
                    pollIntervalMs = 100,
                    timeoutMs = 10000,
                ) as ProotJobClient.AwaitOutcome.Terminal
            ).record
        assertTrue(terminal.state in setOf(ProotJobState.CANCELLED, ProotJobState.ORPHANED))
        assertNotNull(terminal.terminalCommit)
        val reconciled = (client.reconcile(original.jobId) as ProotJobClient.JobStateOutcome.Ok).record
        assertEquals(terminal.terminalCommit, reconciled.terminalCommit)
        assertEquals(original.inputManifestSha256, reconciled.inputManifestSha256)
        assertEquals(original.executionId, reconciled.executionId)
        assertEquals(original.createdAtEpochMs, reconciled.createdAtEpochMs)
        assertNotNull(reconciled.reconciledAtEpochMs)
        assertEquals(reconciled, (client.query(original.jobId) as ProotJobClient.JobStateOutcome.Ok).record)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "PROOT_OWNER_RECOVERED state=${reconciled.state} job=${original.jobId}\n")
            },
        )
        if (final) assertTrue(directory.deleteRecursively())
    }
}
