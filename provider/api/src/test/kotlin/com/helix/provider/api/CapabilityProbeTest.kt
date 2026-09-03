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
        private val visionEvents: List<ModelEvent> =
            listOf(
                ModelEvent.TextDelta("ok"),
                ModelEvent.Completed("stop"),
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

        @Suppress("ReturnCount") // one scripted response shape per request kind
        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            val hasImages = request.messages.any { it.images.isNotEmpty() }
            when {
                hasImages -> {
                    calls += "vision"
                    return flowOf(*visionEvents.toTypedArray())
                }

                request.tools.isEmpty() -> {
                    calls += "text"
                    return flowOf(*textEvents.toTypedArray())
                }

                else -> {
                    calls += "tool"
                    return flowOf(*toolEvents.toTypedArray())
                }
            }
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
                            vision = true, // proved by the passing phase 5
                            reasoning = false,
                            jsonSchemaOutput = false,
                            maxContextTokens = null,
                            source = CapabilitySource.PROBED,
                        ),
                    models = listOf("m1"), // HXA-059: the phase-2 list rides with the Ok
                ),
                outcome,
            )
            assertEquals(listOf("check", "models", "text", "tool", "vision"), provider.calls)
        }

    @Test
    fun listedModelsAreCarriedIntoTheOkOutcome() =
        runBlocking {
            // HXA-059: every listed id reaches the outcome, in order — the app
            // surfaces them so the user can prefill the model field.
            val provider =
                FakeProvider(
                    models =
                        ModelCatalogResult
                            .Listed(
                                listOf(
                                    "fixture-model-a",
                                    "long/path/model/b", // opaque ids may contain separators
                                    "c-3",
                                ),
                            ),
                )
            val outcome = probe.probe(provider) as ProbeOutcome.Ok
            assertEquals(
                listOf("fixture-model-a", "long/path/model/b", "c-3"),
                outcome.models,
            )
        }

    @Test
    fun anOversizedListedModelsListIsTruncatedToTheOutcomeBound() =
        runBlocking {
            // HXA-059 bound: the phase-2 list is capped at
            // MAX_MODELS_IN_OUTCOME (defense against a hostile/buggy provider);
            // the Listed type itself allows up to 1024 ids.
            val ids = List(1_024) { "model-${"%04d".format(it)}" }
            val provider = FakeProvider(models = ModelCatalogResult.Listed(ids))
            val outcome = probe.probe(provider) as ProbeOutcome.Ok
            assertEquals(CapabilityProbe.MAX_MODELS_IN_OUTCOME, outcome.models!!.size)
            assertEquals(ids.take(CapabilityProbe.MAX_MODELS_IN_OUTCOME), outcome.models)
        }

    @Test
    fun normalizeModelIdsDropsBlanksAndDuplicatesPreservingOrder() {
        // HXA-059 normalization (defense in depth over the Listed validation):
        // blanks dropped, duplicates removed, first-seen order kept.
        assertEquals(
            listOf("b", "a"),
            CapabilityProbe.normalizeModelIds(
                listOf("b", "a", "b", "  ", "", "a"),
            ),
        )
    }

    @Test
    fun normalizeModelIdsTruncatesToTheOutcomeBound() {
        val ids = (1..(CapabilityProbe.MAX_MODELS_IN_OUTCOME + 50)).map { "m$it" }
        val normalized = CapabilityProbe.normalizeModelIds(ids)
        assertEquals(CapabilityProbe.MAX_MODELS_IN_OUTCOME, normalized.size)
        assertEquals(ids.take(CapabilityProbe.MAX_MODELS_IN_OUTCOME), normalized)
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
            // HXA-059: no list on an unsupported backend → models = null (the app
            // shows "the backend gives no model list, enter it manually").
            assertEquals(null, (outcome as ProbeOutcome.Ok).models)
            assertEquals(listOf("check", "models", "text", "tool", "vision"), provider.calls)
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
    fun aVisionStreamErrorFailsPhaseFiveNotRetryableForHttpStatus() =
        runBlocking {
            // An image-rejecting endpoint (400) is a NON-retryable phase-5 failure: the probe
            // only ever proves vision by seeing a successful image completion.
            val provider =
                FakeProvider(
                    visionEvents = listOf(ModelEvent.Error(ModelErrorCode.HTTP_ERROR, false)),
                )
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(5, ModelErrorCode.HTTP_ERROR, "vision probe error: HTTP_ERROR", false),
                outcome,
            )
            assertEquals(listOf("check", "models", "text", "tool", "vision"), provider.calls)
        }

    @Test
    fun aVisionStreamServerErrorFailsPhaseFiveRetryable() =
        runBlocking {
            val provider =
                FakeProvider(
                    visionEvents = listOf(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, true)),
                )
            val outcome = probe.probe(provider)
            assertEquals(
                ProbeOutcome.Failed(5, ModelErrorCode.SERVER_ERROR, "vision probe error: SERVER_ERROR", true),
                outcome,
            )
        }

    @Test
    fun aVisionRefusalStillProvesImageAcceptance() =
        runBlocking {
            // A safety refusal to the 1x1 image means the request SHAPE was accepted — the
            // probe's closed rule: Completed or Refusal both prove vision.
            val provider = FakeProvider(visionEvents = listOf(ModelEvent.Refusal("policy")))
            val outcome = probe.probe(provider)
            assertTrue(outcome is ProbeOutcome.Ok)
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

    @Test
    fun overlongToolStreamFailsPhaseFour() =
        runBlocking {
            val many =
                List(CapabilityProbe.MAX_PROBE_EVENTS + 1) {
                    ModelEvent.TextDelta("x")
                } + listOf(ModelEvent.Completed("stop"))
            val provider = FakeProvider(toolEvents = many)
            val outcome = probe.probe(provider)
            assertTrue(outcome is ProbeOutcome.Failed)
            val failed = outcome as ProbeOutcome.Failed
            assertEquals(4, failed.phase)
            assertEquals(ModelErrorCode.PROTOCOL, failed.code)
            assertEquals(false, failed.retryable)
        }
}
