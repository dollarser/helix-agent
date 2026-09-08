package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliRuntimeRunningRecoveryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun codexRunningCancelAndDeathNeverReplay() =
        verifyRunning(
            com.helix.runtime.cli.client.CliModelProvider.CODEX,
        )

    @Test fun claudeRunningCancelAndDeathNeverReplay() =
        verifyRunning(
            com.helix.runtime.cli.client.CliModelProvider.CLAUDE,
        )

    @Test fun grokRunningCancelAndDeathNeverReplay() = verifyRunning(com.helix.runtime.cli.client.CliModelProvider.GROK)

    @Test fun copilotRunningCancelAndDeathNeverReplay() =
        verifyRunning(
            com.helix.runtime.cli.client.CliModelProvider.COPILOT,
        )

    @Test fun interruptedClientWaitCancelsRuntimeJob() {
        val supervisor = CliRuntimeSupervisor(context)
        val client = CliModelJobClient(supervisor)
        val observer = supervisor.openConnection() as com.helix.runtime.cli.client.CliRuntimeConnection.Opened
        val jobId =
            "job_" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val thread =
            java.util.concurrent.atomic
                .AtomicReference<Thread>()
        val worker =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val result =
                worker.submit<CliModelJobClient.AwaitOutcome> {
                    thread.set(Thread.currentThread())
                    client.submitAndAwait(
                        jobId,
                        ModelRequest("helix-fixture-wait", listOf(ModelMessage(ModelRole.USER, "fixture"))),
                    )
                }
            awaitRunning(client, jobId)
            Thread.sleep(100)
            thread.get().interrupt()
            assertEquals(CliModelJobClient.AwaitOutcome.TimedOut, result.get(10, java.util.concurrent.TimeUnit.SECONDS))
            val record = (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record
            assertEquals(CliModelJobState.CANCELLED, record.state)
            assertEquals(record, (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record)
            assertEquals(null, (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).events)
        } finally {
            client.cancel(jobId)
            worker.shutdownNow()
            supervisor.closeConnection(observer)
        }
    }

    private fun verifyRunning(platform: com.helix.runtime.cli.client.CliModelProvider) {
        for (kill in listOf(false, true)) verifyInterruption(platform, kill)
    }

    private fun verifyInterruption(
        platform: com.helix.runtime.cli.client.CliModelProvider,
        kill: Boolean,
    ) {
        val client = CliModelJobClient(CliRuntimeSupervisor(context))
        val jobId =
            "job_" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val worker =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val running =
                worker.submit<CliModelJobClient.AwaitOutcome> {
                    client.submitAndAwait(
                        jobId,
                        ModelRequest("helix-fixture-wait", listOf(ModelMessage(ModelRole.USER, "fixture"))),
                        provider = platform,
                    )
                }
            awaitRunning(client, jobId)
            // RUNNING is durable before the model body starts; allow the debug wait seam to install.
            Thread.sleep(100)
            if (kill) client.debugKillRuntime() else client.cancel(jobId)
            running.get(10, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(200)
            val record = (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record
            assertEquals(if (kill) CliModelJobState.INTERRUPTED else CliModelJobState.CANCELLED, record.state)
            assertEquals(record, (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record)
            assertEquals(null, (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).events)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun awaitRunning(
        client: CliModelJobClient,
        jobId: String,
    ) {
        var state: CliModelJobState? = null
        var remaining = 100
        while (remaining-- > 0 && state != CliModelJobState.RUNNING) {
            state = (client.query(jobId) as? CliModelJobClient.StateOutcome.Ok)?.record?.state
            if (state != CliModelJobState.RUNNING) Thread.sleep(20)
        }
        assertEquals(CliModelJobState.RUNNING, state)
    }
}
