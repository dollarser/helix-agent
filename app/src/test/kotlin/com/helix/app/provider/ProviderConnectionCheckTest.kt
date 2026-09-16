package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderConnectionCheckTest {
    @Test fun reasoningOnlyCompletedStreamProvesConnection() =
        runBlocking {
            val provider =
                Fixture(
                    null,
                    listOf(
                        ModelEvent.ReasoningDelta("thinking"),
                        ModelEvent.Completed("length"),
                        ModelEvent.Usage(10, 16),
                    ),
                )
            val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Ok
            assertEquals(CapabilitySource.CONNECTION_ONLY, result.capabilities.source)
            assertEquals(1, provider.generations)
        }

    @Test fun emptyWhitespaceAndUnfinishedReasoningFailProtocol() =
        runBlocking {
            val invalid =
                listOf(
                    listOf(ModelEvent.Completed("stop")),
                    listOf(ModelEvent.ReasoningDelta(" "), ModelEvent.Completed("length")),
                    listOf(ModelEvent.ReasoningDelta("thinking"), ModelEvent.Usage(10, 16)),
                    listOf(ModelEvent.Completed("stop"), ModelEvent.ReasoningDelta("late")),
                )
            for (events in invalid) {
                val result = ProviderConnectionCheck.run(config, Fixture(null, events), null) as ProbeOutcome.Failed
                assertEquals(ModelErrorCode.PROTOCOL, result.code)
                assertEquals(3, result.phase)
            }
        }

    @Test fun errorAfterReasoningIsNotAConnectedResult() =
        runBlocking {
            val provider =
                Fixture(
                    null,
                    listOf(
                        ModelEvent.ReasoningDelta("thinking"),
                        ModelEvent.Completed("length"),
                        ModelEvent.Error(ModelErrorCode.TRANSPORT, true),
                    ),
                )
            val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Failed
            assertEquals(ModelErrorCode.TRANSPORT, result.code)
        }

    @Test fun trailingUsageDoesNotInvalidateACompletedReply() =
        runBlocking {
            val provider =
                Fixture(
                    null,
                    listOf(
                        ModelEvent.TextDelta("ok"),
                        ModelEvent.Completed("stop"),
                        ModelEvent.Usage(10, 2),
                    ),
                )
            org.junit.Assert.assertTrue(ProviderConnectionCheck.run(config, provider, null) is ProbeOutcome.Ok)
        }

    @Test fun usageWithoutCompletionStillFails() =
        runBlocking {
            val provider = Fixture(null, listOf(ModelEvent.TextDelta("ok"), ModelEvent.Usage(10, 2)))
            org.junit.Assert.assertTrue(ProviderConnectionCheck.run(config, provider, null) is ProbeOutcome.Failed)
        }

    private val config =
        ProviderConfig(
            "fixture",
            "fixture",
            ProviderProtocol.OPENAI_RESPONSES,
            NormalizedEndpoint.parse("https://fixture.invalid"),
            "expensive-unavailable-model",
            emptyMap(),
            SecretAlias("fixture"),
            "{}",
        )

    @Test fun authenticatedCatalogDoesNotGenerateOrDependOnConfiguredModel() =
        runBlocking {
            val provider = Fixture(ModelCatalogResult.Listed(listOf("different-model")))
            val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Ok
            assertEquals(listOf("different-model"), result.models)
            assertEquals(CapabilitySource.CONNECTION_ONLY, result.capabilities.source)
            assertEquals(0, provider.generations)
            assertEquals(0, provider.fallbackCatalogCalls)
        }

    @Test fun failedAccountDoesNotGenerateOrFallBackToCachedModels() =
        runBlocking {
            val provider = Fixture(ModelCatalogResult.Failed(ModelErrorCode.AUTH, "rejected", false))
            val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Failed
            assertEquals(ModelErrorCode.AUTH, result.code)
            assertEquals(0, provider.generations)
            assertEquals(0, provider.fallbackCatalogCalls)
        }

    @Test fun localCatalogWithoutAccountVerificationStillRequiresRealConnection() =
        runBlocking {
            val provider = Fixture(null)
            val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Failed
            assertEquals(ModelErrorCode.TRANSPORT, result.code)
            assertEquals(1, provider.generations)
            assertEquals(1, provider.fallbackCatalogCalls)
        }

    @Test fun unsupportedAccountCheckCannotClaimConnection() =
        runBlocking {
            val provider = Fixture(ModelCatalogResult.Unsupported)
            val result = ProviderConnectionCheck.run(config, provider, null) as ProbeOutcome.Failed
            assertEquals(ModelErrorCode.TRANSPORT, result.code)
            assertEquals(1, provider.generations)
        }

    private inner class Fixture(
        private val account: ModelCatalogResult?,
        private val events: List<ModelEvent> = listOf(ModelEvent.Error(ModelErrorCode.TRANSPORT, true)),
    ) : ModelProvider,
        SubscriptionConnectionProvider {
        var generations = 0
        var fallbackCatalogCalls = 0
        override val descriptor =
            ProviderDescriptor(config.id, config.displayName, config.protocol, config.model, config.endpoint)

        override suspend fun connectionCatalog() = account

        override suspend fun listModels(): ModelCatalogResult {
            fallbackCatalogCalls++
            return ModelCatalogResult.Listed(listOf(config.model))
        }

        override suspend fun validateConfiguration(): ProviderCheckResult = error("not used")

        override fun stream(request: ModelRequest) =
            flowOf(*events.toTypedArray()).also {
                generations++
            }
    }
}
