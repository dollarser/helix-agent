package com.helix.provider.api.local

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.core.model.ProviderTransport
import com.helix.core.model.ToolCallId
import com.helix.provider.api.CapabilityProbe
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelProviderTest {
    private val asset = ModelAssetRef("model", "a".repeat(64), 1024)
    private val loading = LocalModelLoadRequest(asset, 2048, 2)
    private val config =
        ProviderConfig(
            "local",
            "Local",
            ProviderConnection(
                ProviderProvisioningKind.ON_DEVICE_ASSET,
                ProviderTransport.OnDeviceLocal,
                ProviderAuth.None,
            ),
            "model",
            emptyMap(),
            "{}",
        )

    @Test
    fun localConfigHasNoNetworkFields() {
        assertEquals(ProviderResidence.ON_DEVICE_LOCAL, config.residence())
        assertThrows(IllegalArgumentException::class.java) { config.network }
        assertThrows(IllegalArgumentException::class.java) { config.copy(headers = mapOf("x-test" to "value")) }
    }

    @Test
    fun missingOrRepeatedTerminalFailsClosed() =
        runBlocking {
            for (events in listOf(emptyList(), listOf(ModelEvent.Completed("stop"), ModelEvent.TextDelta("late")))) {
                val runtime = FakeRuntime(events)
                val output = LocalModelProvider(config, asset, runtime, loading).stream(request()).toList()
                assertEquals(listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)), output)
                assertTrue(runtime.cancelled)
            }
        }

    @Test
    fun normalStreamAndUnconfirmedExit() =
        runBlocking {
            val events = listOf(ModelEvent.TextDelta("hello"), ModelEvent.Usage(1, 1), ModelEvent.Completed("stop"))
            val runtime = FakeRuntime(events)
            assertEquals(events, LocalModelProvider(config, asset, runtime, loading).stream(request()).toList())
            assertFalse(runtime.terminated)
            runtime.exit = LocalCancelResult.ACKNOWLEDGED
            val interrupted = LocalModelProvider(config, asset, runtime, loading).stream(request()).toList()
            assertEquals(ModelEvent.Error(ModelErrorCode.LOCAL_CANCEL_TIMEOUT, false), interrupted.last())
            assertTrue(runtime.terminated)
        }

    @Test
    fun standardCapabilityProbeCanDiscoverLocalToolCalls() =
        runBlocking {
            val runtime = FakeRuntime(listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")))
            runtime.respondToTools = true
            val outcome = CapabilityProbe().probe(LocalModelProvider(config, asset, runtime, loading))
            assertTrue(outcome is ProbeOutcome.Ok)
            assertTrue((outcome as ProbeOutcome.Ok).capabilities.toolCalls)
        }

    @Test
    fun localFailuresRemainDistinctFromNetworkErrors() =
        runBlocking {
            val runtime = FakeRuntime(listOf(ModelEvent.Error(ModelErrorCode.LOCAL_RUNTIME_OOM, false)))
            assertEquals(
                listOf(ModelEvent.Error(ModelErrorCode.LOCAL_RUNTIME_OOM, false)),
                LocalModelProvider(config, asset, runtime, loading).stream(request()).toList(),
            )
        }

    @Test
    fun failedCancelCannotPublishSuccess() =
        runBlocking {
            val runtime = FakeRuntime(listOf(ModelEvent.Completed("stop")))
            runtime.cancelFails = true
            val events = LocalModelProvider(config, asset, runtime, loading).stream(request()).toList()
            assertEquals(ModelEvent.Error(ModelErrorCode.LOCAL_CANCEL_TIMEOUT, false), events.last())
            assertTrue(runtime.terminated)
        }

    @Test
    fun collectorCancellationWaitsForExecutorCleanup() =
        runBlocking {
            val runtime = FakeRuntime(emptyList())
            runtime.hang = true
            val job = launch { LocalModelProvider(config, asset, runtime, loading).stream(request()).toList() }
            runtime.started.await()
            job.cancelAndJoin()
            assertTrue(runtime.cancelled)
            assertTrue(job.isCancelled)
        }

    @Test
    fun metadataLoadsTheConfiguredContextBeforeInspecting() =
        runBlocking {
            val runtime = FakeRuntime(emptyList())
            val configured = loading.copy(contextTokens = 8192)
            val provider = LocalModelProvider(config, asset, runtime, configured)
            provider.modelMetadata()
            assertEquals(configured, runtime.lastLoad)
            assertEquals(configured, runtime.inspectedLoad)
        }

    private fun request() = ModelRequest(model = "model", messages = listOf(ModelMessage(ModelRole.USER, "Hello")))

    private inner class FakeRuntime(
        private val events: List<ModelEvent>,
    ) : LocalInferenceRuntimePort {
        var hang = false
        val started = CompletableDeferred<Unit>()
        var cancelFails = false
        var respondToTools = false
        var cancelled = false
        var terminated = false
        var exit = LocalCancelResult.EXITED
        var lastLoad: LocalModelLoadRequest? = null
        var inspectedLoad: LocalModelLoadRequest? = null

        override suspend fun inspect(asset: ModelAssetRef): LocalModelInspection {
            inspectedLoad = lastLoad
            return LocalModelInspection(
                asset,
                ModelMetadata(contextWindow = 2048),
                ProviderCapabilities(false, false, false, false, false, false, 2048, CapabilitySource.MANUAL),
            )
        }

        override suspend fun load(request: LocalModelLoadRequest): LoadedLocalModel {
            lastLoad = request
            return LoadedLocalModel("handle", request)
        }

        override fun generate(request: LocalGenerationRequest): Flow<ModelEvent> =
            if (hang) {
                flow {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } else if (respondToTools && request.request.tools.isNotEmpty()) {
                listOf(
                    ModelEvent.ToolCallStarted(0, ToolCallId("probe-1"), "echo"),
                    ModelEvent.ToolArgumentsDelta(0, "{\"text\":\"probe\"}"),
                    ModelEvent.ToolCallFinished(0),
                    ModelEvent.Completed("tool_calls"),
                ).asFlow()
            } else {
                events.asFlow()
            }

        override suspend fun cancel(generationId: String): LocalCancelResult {
            cancelled = true
            if (cancelFails) throw LocalRuntimeException(ModelErrorCode.LOCAL_RUNTIME_CRASHED)
            return exit
        }

        override suspend fun unload(modelHandle: String) = LocalUnloadResult.UNLOADED

        override suspend fun runtimeStatus() = LocalRuntimeStatus(null, null)

        override suspend fun terminate() {
            terminated = true
        }
    }
}
