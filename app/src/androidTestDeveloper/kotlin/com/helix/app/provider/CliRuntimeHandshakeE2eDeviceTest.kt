package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import com.helix.runtime.cli.client.CliRuntimeVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliRuntimeHandshakeE2eDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun oldRuntimeRejectsClaudeEnvelopeWithoutCreatingJob() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("oldRuntime") == "true")
        val client = CliModelJobClient(CliRuntimeSupervisor(context))
        val jobId = nextJobId()
        val result =
            client.submitAndAwait(
                jobId,
                fixture(),
                provider = com.helix.runtime.cli.client.CliModelProvider.CLAUDE,
            )
        assertTrue(result is CliModelJobClient.AwaitOutcome.Unavailable)
        assertTrue(client.query(jobId) is CliModelJobClient.StateOutcome.Unknown)
        assertTrue(client.submitAndAwait(nextJobId(), fixture()) is CliModelJobClient.AwaitOutcome.Terminal)
    }

    private fun nextJobId() =
        "job_" +
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

    private fun fixture(model: String = "helix-fixture") =
        ModelRequest(model, listOf(ModelMessage(ModelRole.USER, "fixture")))

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
        val jobId = nextJobId()
        val awaited = client.submitAndAwaitFixed(jobId, timeoutMs = 2_000, pollIntervalMs = 20)
        assertTrue(awaited is CliModelJobClient.AwaitOutcome.Terminal)
        val record = (awaited as CliModelJobClient.AwaitOutcome.Terminal).record
        assertEquals(CliModelJobState.FAILED, record.state)
        val duplicate = client.submitAndAwaitFixed(jobId, timeoutMs = 2_000, pollIntervalMs = 20)
        assertEquals(record, (duplicate as CliModelJobClient.AwaitOutcome.Terminal).record)
        assertEquals(null, record.reconciledAtEpochMillis)
        val reconciled = (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).record
        assertTrue(reconciled.reconciledAtEpochMillis != null)
        assertEquals(record, reconciled.copy(reconciledAtEpochMillis = null))
        client.debugKillRuntime()
        Thread.sleep(200)
        assertEquals(reconciled, (client.query(jobId) as CliModelJobClient.StateOutcome.Ok).record)
        assertEquals(reconciled, (client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok).record)
        val repeated = client.submitAndAwaitFixed(jobId, timeoutMs = 2_000, pollIntervalMs = 20)
        assertEquals(reconciled, (repeated as CliModelJobClient.AwaitOutcome.Terminal).record)
        assertTrue(client.query("job_ffffffffffff") is CliModelJobClient.StateOutcome.Unknown)
        assertTrue(client.cancel("job_ffffffffffff") is CliModelJobClient.StateOutcome.Unknown)
    }

    @Test fun modelPayloadUsesPfdAndIsDeletedAfterReconcile() =
        verifyPayload(com.helix.runtime.cli.client.CliModelProvider.CODEX)

    @Test fun claudePayloadSurvivesDisconnectAndIsDeletedAfterReconcile() =
        verifyPayload(com.helix.runtime.cli.client.CliModelProvider.CLAUDE)

    @Test fun grokPayloadSurvivesDisconnectAndIsDeletedAfterReconcile() =
        verifyPayload(com.helix.runtime.cli.client.CliModelProvider.GROK)

    @Test fun copilotPayloadSurvivesDisconnectAndIsDeletedAfterReconcile() =
        verifyPayload(com.helix.runtime.cli.client.CliModelProvider.COPILOT)

    private fun verifyPayload(platform: com.helix.runtime.cli.client.CliModelProvider) {
        val client = CliModelJobClient(CliRuntimeSupervisor(context))
        val jobId =
            "job_" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val result =
            client.submitAndAwait(
                jobId,
                ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "bounded payload"))),
                timeoutMs = 2_000,
                pollIntervalMs = 20,
                provider = platform,
            )
        assertTrue(result is CliModelJobClient.AwaitOutcome.Terminal)
        result as CliModelJobClient.AwaitOutcome.Terminal
        assertEquals(CliModelJobState.SUCCEEDED, result.record.state)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("HELIX_OK"), ModelEvent.Usage(2, 1), ModelEvent.Completed("stop")),
            result.events,
        )
        assertEquals(null, result.record.reconciledAtEpochMillis)
        client.debugKillRuntime()
        Thread.sleep(200)
        val recovered = client.fetchResult(jobId) as CliModelJobClient.StateOutcome.Ok
        assertEquals(result.events, recovered.events)
        assertEquals(result.record.requestSha256, recovered.record.requestSha256)
        assertEquals(result.record.outputSha256, recovered.record.outputSha256)
        assertEquals(null, recovered.record.reconciledAtEpochMillis)
        // The test explicitly discards its verified fixture after checking it; reads alone must not ACK.
        val acknowledged = client.acknowledgeResult(recovered.record) as CliModelJobClient.StateOutcome.Ok
        assertTrue(acknowledged.record.reconciledAtEpochMillis != null)
        client.debugKillRuntime()
        Thread.sleep(200)
        val second = client.reconcile(jobId) as CliModelJobClient.StateOutcome.Ok
        assertTrue(second.record.reconciledAtEpochMillis != null)
        assertEquals(null, second.events)
    }
}
