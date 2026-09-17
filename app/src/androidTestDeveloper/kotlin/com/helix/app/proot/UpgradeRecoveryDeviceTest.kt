package com.helix.app.proot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * HXA-193 upgrade-recovery acceptance: seed the on-disk state a previous app version leaves
 * behind (a finished job result with its payload, a half-done job, host config and user data),
 * then prove the new version's cold :subscriptions start recovers it without re-execution:
 * the finished result stays fetchable and reconciles exactly once, the half-done job settles
 * to INTERRUPTED, and host config/user data survive the recovery cycle. Legacy verification
 * anchor non-activation is covered by IntegratedRuntimeDeviceTest in the same suite.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeRecoveryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = CliModelJobClient(CliRuntimeSupervisor(context))
    private val seededJobDirs = mutableListOf<File>()

    @After
    fun clearSeededJournalState() {
        seededJobDirs.forEach { it.deleteRecursively() }
        seededJobDirs.clear()
    }

    @Test
    fun priorVersionSucceededJobKeepsResultAndReconcilesExactlyOnce() {
        val jobId = "job_000000000001"
        val requestSha = sha256Hex("prior-version-request")
        val events = listOf(ModelEvent.TextDelta("prior-version-result"), ModelEvent.Completed("stop"))
        val output = CliModelEventCodec.encode(events)
        val outputSha = sha256Hex(output)
        val finishedAt = System.currentTimeMillis() - HOUR_MS
        seedRecord(
            jobId,
            CliModelJobRecord(
                jobId,
                requestSha,
                CliModelJobState.SUCCEEDED,
                finishedAt - 1000L,
                finishedAt,
                "gpt-6-astra",
                outputSha,
            ),
        )
        File(jobDir(jobId), "events.json").writeBytes(output)

        client.debugKillRuntime()

        val fetched = client.fetchResult(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(CliModelJobState.SUCCEEDED, fetched.record.state)
        assertEquals(outputSha, fetched.record.outputSha256)
        assertEquals(events, fetched.events)

        val pendingReconcile = client.query(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(CliModelJobState.SUCCEEDED, pendingReconcile.record.state)
        assertNull(pendingReconcile.record.reconciledAtEpochMillis)

        val consumed = client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(CliModelJobState.SUCCEEDED, consumed.record.state)
        assertEquals(events, consumed.events)

        val settled = awaitReconciled(jobId)
        assertEquals(CliModelJobState.SUCCEEDED, settled.state)
        assertNotNull(settled.reconciledAtEpochMillis)
        assertEquals(null, (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).events)
        val after = client.query(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(CliModelJobState.SUCCEEDED, after.record.state)
    }

    @Test
    fun priorVersionHalfDoneJobSettlesInterruptedWithoutReplay() {
        val jobId = "job_000000000002"
        val requestSha = sha256Hex("prior-version-half-done-request")
        seedRecord(
            jobId,
            CliModelJobRecord(jobId, requestSha, CliModelJobState.PENDING, System.currentTimeMillis() - HOUR_MS),
        )
        File(jobDir(jobId), "request.json").writeBytes("partial prior-version request".toByteArray())

        client.debugKillRuntime()

        val settled = client.query(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(CliModelJobState.INTERRUPTED, settled.record.state)
        assertNotNull(settled.record.terminalAtEpochMillis)
        assertEquals(requestSha, settled.record.requestSha256)
        assertEquals(null, (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).events)
        val after = client.query(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(CliModelJobState.INTERRUPTED, after.record.state)
    }

    @Test
    fun existingConfigAndUserDataSurviveRuntimeRecoveryCycle() {
        val prefs = context.getSharedPreferences("upgrade_recovery_probe", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString("probe.title", "upgrade-probe-title")
            .putInt("probe.turns", 7)
            .apply()
        val userData = File(context.filesDir, "upgrade-probe-user-data.json")
        userData.writeText("""{"kind":"upgrade-probe","payload":"keep-me"}""")

        client.debugKillRuntime()

        assertEquals(CliModelJobClient.StateOutcome.Unknown, client.query("job_deadbeef0000"))
        val reloaded = context.getSharedPreferences("upgrade_recovery_probe", Context.MODE_PRIVATE)
        assertEquals("upgrade-probe-title", reloaded.getString("probe.title", null))
        assertEquals(7, reloaded.getInt("probe.turns", -1))
        assertEquals("""{"kind":"upgrade-probe","payload":"keep-me"}""", userData.readText())
    }

    private fun awaitReconciled(jobId: String): CliModelJobRecord {
        repeat(100) {
            val record = (client.query(jobId) as? CliModelJobClient.StateOutcome.Ok)?.record
            if (record?.reconciledAtEpochMillis != null) return record
            Thread.sleep(100)
        }
        throw AssertionError("seeded job was never reconciled after cold start")
    }

    private fun jobDir(jobId: String) = File(context.filesDir, "provider-v1/codex-model-jobs/$jobId")

    private fun seedRecord(
        jobId: String,
        record: CliModelJobRecord,
    ) {
        val dir = jobDir(jobId)
        dir.mkdirs()
        File(dir, "record.json").writeText(CliModelJobRecordCodec.encode(record))
        seededJobDirs.add(dir)
    }

    private fun sha256Hex(input: String) = sha256Hex(input.toByteArray())

    private fun sha256Hex(input: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val HOUR_MS = 60L * 60 * 1000
    }
}
