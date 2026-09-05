package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import com.helix.runtime.cli.client.CliRuntimeVerification
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliRuntimeHandshakeE2eDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun explicitColdBindOrExpectedLocalRefusalIsStable() {
        val result = CliRuntimeSupervisor(context).verify()
        val expectedCause = InstrumentationRegistry.getArguments().getString("cliRuntimeExpectedCause")
        if (expectedCause != null) {
            assertTrue(result is CliRuntimeVerification.Unavailable)
            assertEquals(expectedCause, (result as CliRuntimeVerification.Unavailable).cause.name)
            return
        }
        assertTrue(result is CliRuntimeVerification.Verified)
        val status = (result as CliRuntimeVerification.Verified).status
        assertEquals(CliRuntimeProtocol.VERSION, status.protocolVersion)
        assertEquals("arm64-v8a", status.abi)
        assertEquals("NOT_REGISTERED", status.agentBackendState)
    }

    @Test fun fixedModelJobIsDurableAndNeverBlindlyResubmitted() {
        val client = CliModelJobClient(CliRuntimeSupervisor(context))
        val jobId = "job_132000000001"
        val awaited = client.submitAndAwaitFixed(jobId, timeoutMs = 2_000, pollIntervalMs = 20)
        assertTrue(awaited is CliModelJobClient.AwaitOutcome.Terminal)
        val record = (awaited as CliModelJobClient.AwaitOutcome.Terminal).record
        assertEquals(CliModelJobState.FAILED, record.state)
        val duplicate = client.submitAndAwaitFixed(jobId, timeoutMs = 2_000, pollIntervalMs = 20)
        assertEquals(record, (duplicate as CliModelJobClient.AwaitOutcome.Terminal).record)
        assertEquals(record, (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).record)
        client.debugKillRuntime()
        Thread.sleep(200)
        assertEquals(record, (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record)
        assertTrue(client.query("job_ffffffffffff") is CliModelJobClient.StateOutcome.Unknown)
        assertTrue(client.cancel("job_ffffffffffff") is CliModelJobClient.StateOutcome.Unknown)
    }

    @Test fun modelPayloadUsesPfdAndIsDeletedAfterReconcile() {
        val client = CliModelJobClient(CliRuntimeSupervisor(context))
        val result = client.submitAndAwait(
            "job_134000000001",
            ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "bounded payload"))),
            timeoutMs = 2_000,
            pollIntervalMs = 20,
        )
        assertTrue(result is CliModelJobClient.AwaitOutcome.Terminal)
        result as CliModelJobClient.AwaitOutcome.Terminal
        assertEquals(CliModelJobState.SUCCEEDED, result.record.state)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("HELIX_OK"), ModelEvent.Usage(2, 1), ModelEvent.Completed("stop")),
            result.events,
        )
        repeat(50) {
            val record = (client.query("job_134000000001") as CliModelJobClient.StateOutcome.Ok).record
            if (record.reconciledAtEpochMillis != null) return@repeat
            Thread.sleep(20)
        }
        val second = client.reconcile("job_134000000001") as CliModelJobClient.StateOutcome.Ok
        assertTrue(second.record.reconciledAtEpochMillis != null)
        assertEquals(null, second.events)
    }
}
