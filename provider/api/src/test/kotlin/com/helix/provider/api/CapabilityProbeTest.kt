package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProbeTest {
    /** A scripted ModelProvider: records which methods ran and returns canned results. */
    private class FakeProvider(
        private val check: ProviderCheckResult = ProviderCheckResult.Ok,
        private val models: ModelCatalogResult =
            ModelCatalogResult.Listed(listOf("m1")),
        private val textEvents: List<ModelEvent> =
            listOf(
                ModelEvent.TextDelta("ok"),
                ModelEvent.Completed("stop"),
            ),
        private val toolEvents: List<ModelEvent> =
            listOf(
                ModelEvent.ToolCallStarted(
                    0,
                    com.helix.core.model
                        .ToolCallId("call_1"),
                    "echo",
                ),
                ModelEvent.ToolArgumentsDelta(0, "{\"text\":\"probe\"}"),
                ModelEvent.ToolCallFinished(0),
                ModelEvent.Completed("tool_calls"),
            ),
    ) : ModelProvider {
        val calls = ArrayList<String>()

        override val descriptor: ProviderDescriptor =
            ProviderDescriptor(
                id = "p",
                displayName = "Fake",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                model = "m1",
                endpoint = NormalizedEndpoint.parse("https://example.test:443/v1"),
            )

        override suspend fun listModels(): ModelCatalogResult {
            calls += "models"
            return models
        }

        override suspend fun validateConfiguration(): ProviderCheckResult {
            calls += "check"
            return check
        }

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            val events = if (request.tools.isEmpty()) textEvents else toolEvents
            calls += if (request.tools.isEmpty()) "text" else "tool"
            return flowOf(*events.toTypedArray())
        }
    }

    private val probe = CapabilityProbe()

    @Test
    fun happyPathDerivesProbedCapabilities() =
        runBlocking {
            val provider = FakeProvider()
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Ok(
                    capabilities =
                        ProviderCapabilities(
                            streaming = true,
                            toolCalls = true,
                            parallelToolCalls = false,
                            vision = false,
                            reasoning = false,
                            jsonSchemaOutput = false,
                            maxContextTokens = null,
                            source = CapabilitySource.PROBED,
                        ),
                    models = null,
                ),
                outcome,
            )
            assertEquals(listOf("check", "models", "text", "tool"), provider.calls)
        }

    @Test
    fun phaseOneFailureStopsTheProbe() =
        runBlocking {
            val provider =
                FakeProvider(
                    check =
                        ProviderCheckResult.Failed(
                            ModelErrorCode.AUTH,
                            "http 401",
                            retryable = false,
                        ),
                )
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(1, ModelErrorCode.AUTH, "http 401", false),
                outcome,
            )
            assertEquals(listOf("check"), provider.calls)
        }

    @Test
    fun phaseTwoFailureStopsTheProbe() =
        runBlocking {
            val provider =
                FakeProvider(
                    models =
                        ModelCatalogResult.Failed(
                            ModelErrorCode.RATE_LIMITED,
                            "http 429",
                            retryable = true,
                        ),
                )
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(2, ModelErrorCode.RATE_LIMITED, "http 429", true),
                outcome,
            )
            assertEquals(listOf("check", "models"), provider.calls)
        }

    @Test
    fun unsupportedModelListCountsAsPass() =
        runBlocking {
            val provider = FakeProvider(models = ModelCatalogResult.Unsupported)
            val outcome = probe.probe(provider)
            assertEquals(ProbeOutcome.Ok::class, outcome::class)
            assertEquals(listOf("check", "models", "text", "tool"), provider.calls)
        }

    @Test
    fun textStreamErrorFailsPhaseThree() =
        runBlocking {
            val provider =
                FakeProvider(
                    textEvents = listOf(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, true)),
                )
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(
                    3,
                    ModelErrorCode.SERVER_ERROR,
                    "text stream error: SERVER_ERROR",
                    true,
                ),
                outcome,
            )
            assertEquals(listOf("check", "models", "text"), provider.calls)
        }

    @Test
    fun textStreamWithoutTerminalFailsPhaseThree() =
        runBlocking {
            val provider = FakeProvider(textEvents = listOf(ModelEvent.TextDelta("no terminal")))
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(3, ModelErrorCode.PROTOCOL, "text stream had no terminal", true),
                outcome,
            )
        }

    @Test
    fun refusalPassesTheTextPhase() =
        runBlocking {
            val provider =
                FakeProvider(
                    textEvents = listOf(ModelEvent.Refusal("policy")),
                )
            val outcome = probe.probe(provider)
            assertEquals(ProbeOutcome.Ok::class, outcome::class)
        }

    @Test
    fun toolFixtureWithoutCompletedCallFailsPhaseFour() =
        runBlocking {
            val provider =
                FakeProvider(
                    toolEvents =
                        listOf(
                            ModelEvent.ToolCallStarted(
                                0,
                                com.helix.core.model
                                    .ToolCallId("c"),
                                "echo",
                            ),
                            ModelEvent.Completed("stop"),
                        ),
                )
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(
                    4,
                    ModelErrorCode.PROTOCOL,
                    "tool fixture did not complete a tool call (finishReason=stop)",
                    false,
                ),
                outcome,
            )
        }

    @Test
    fun toolFixtureRefusalFailsPhaseFour() =
        runBlocking {
            val provider = FakeProvider(toolEvents = listOf(ModelEvent.Refusal("refused")))
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(
                    4,
                    ModelErrorCode.PROTOCOL,
                    "the model refused the tool fixture",
                    false,
                ),
                outcome,
            )
        }

    @Test
    fun overlongStreamFailsTheProbe() =
        runBlocking {
            val many =
                List(CapabilityProbe.MAX_PROBE_EVENTS + 1) {
                    ModelEvent.TextDelta("x")
                } + listOf(ModelEvent.Completed("stop"))
            val provider = FakeProvider(textEvents = many)
            val outcome = probe.probe(provider)
            assertTrue(outcome is ProbeOutcome.Failed)
            val failed = outcome as ProbeOutcome.Failed
            assertEquals(3, failed.phase)
            assertEquals(ModelErrorCode.PROTOCOL, failed.code)
            assertEquals(false, failed.retryable)
        }
}
