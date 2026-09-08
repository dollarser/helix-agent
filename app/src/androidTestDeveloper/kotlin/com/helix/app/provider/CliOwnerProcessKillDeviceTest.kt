package com.helix.app.provider

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Direct production Runtime client and debug wait model; no account or ChatService acceptance. */
class CliOwnerProcessKillDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val marker get() = File(context.filesDir, "cli-owner-kill.json")
    private val client get() = CliModelJobClient(CliRuntimeSupervisor(context))

    @Test fun originalJobSurvivesOwnerDeathWithoutResubmission() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("cli.owner.phase")
        assumeTrue("Requires the dedicated host SIGKILL runner", phase != null)
        when (phase) {
            "prepare" -> prepare(CliModelProvider.valueOf(requireNotNull(args.getString("cli.owner.provider"))))
            "recover", "recover-final" -> recover(phase == "recover-final")
            else -> error("Unexpected host phase")
        }
    }

    private fun prepare(provider: CliModelProvider) {
        check(!marker.exists()) { "Recover the existing owned Job first" }
        val id =
            "job_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val running =
                worker.submit<CliModelJobClient.AwaitOutcome> {
                    client.submitAndAwait(
                        id,
                        ModelRequest("helix-fixture-wait", listOf(ModelMessage(ModelRole.USER, "fixture"))),
                        provider = provider,
                    )
                }
            var record = (client.query(id) as? CliModelJobClient.StateOutcome.Ok)?.record
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (record?.state != CliModelJobState.RUNNING) {
                assertTrue("CLI Job did not reach RUNNING", android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(25)
                record = (client.query(id) as? CliModelJobClient.StateOutcome.Ok)?.record
            }
            marker.writeText(CliModelJobRecordCodec.encode(requireNotNull(record)))
            Thread.sleep(100)
            assertTrue("Wait fixture ended before the kill boundary", !running.isDone)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "CLI_OWNER_KILL_READY pid=${android.os.Process.myPid()} job=$id\n")
                },
            )
            Thread.sleep(15000)
            error("Host did not kill the waiting client")
        } finally {
            worker.shutdownNow()
        }
    }

    private fun recover(final: Boolean) {
        val original = CliModelJobRecordCodec.decode(marker.readText())
        val state = client.query(original.jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(original.jobId, state.record.jobId)
        assertEquals(original.requestSha256, state.record.requestSha256)
        assertEquals(original.createdAtEpochMillis, state.record.createdAtEpochMillis)
        if (!state.record.state.terminal) client.cancel(original.jobId)
        var terminal = client.query(original.jobId) as CliModelJobClient.StateOutcome.Ok
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (!terminal.record.state.terminal) {
            assertTrue("Original CLI Job did not settle", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(25)
            terminal = client.query(original.jobId) as CliModelJobClient.StateOutcome.Ok
        }
        assertTrue(terminal.record.state in setOf(CliModelJobState.CANCELLED, CliModelJobState.INTERRUPTED))
        val reconciled = client.reconcile(original.jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(terminal.record.state, reconciled.record.state)
        assertEquals(null, reconciled.events)
        assertEquals(original.requestSha256, reconciled.record.requestSha256)
        assertEquals(original.createdAtEpochMillis, reconciled.record.createdAtEpochMillis)
        assertEquals(reconciled.record, (client.query(original.jobId) as CliModelJobClient.StateOutcome.Ok).record)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "CLI_OWNER_RECOVERED state=${reconciled.record.state} job=${original.jobId}\n")
            },
        )
        if (final) check(marker.delete())
    }
}
