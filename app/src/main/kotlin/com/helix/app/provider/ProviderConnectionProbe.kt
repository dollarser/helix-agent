package com.helix.app.provider

import com.helix.core.model.Clock
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilityProbe
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig

@Suppress("LongParameterList")
internal class ProviderConnectionProbe(
    private val storage: HelixStorage,
    private val factory: ProviderFactory,
    private val testStatus: ProviderTestStatusStore,
    private val probe: CapabilityProbe,
    private val clock: Clock,
    private val managed: ManagedProviderHooks,
    private val storedConfig: suspend (String) -> ProviderConfig,
    private val discoverContextWindow: suspend (String, String) -> ProviderContextSettings,
    private val onNetworkOperation: () -> Unit,
) {
    /** The probe itself; only ever run on the service's IO scope (network + Room). */
    suspend fun run(providerId: String): ProbeOutcome {
        val config = storedConfig(providerId)
        val provider = factory.create(config)
        onNetworkOperation()
        val outcome = managed.probe(config, provider) ?: probe.probe(provider)
        when (outcome) {
            is ProbeOutcome.Ok -> {
                discoverContextWindow(providerId, config.model)
                storage.providerConfigs.overwrite(
                    storage.providerConfigs.resolve(providerId).let { e ->
                        ProviderConfigSpec(
                            id = e.id,
                            displayName = e.displayName,
                            protocol = ProviderProtocol.parse(e.protocol),
                            endpoint = e.endpoint,
                            model = e.model,
                            headersJson = e.headersJson,
                            secretAlias = e.secretAlias,
                            capabilitySnapshot = ProviderCapabilities.toJsonString(outcome.capabilities),
                        )
                    },
                )
                testStatus.recordPassed(
                    providerId,
                    clock.now().toEpochMilli(),
                    outcome.capabilities,
                    outcome.models,
                )
            }

            is ProbeOutcome.Failed -> {
                testStatus.recordFailed(
                    providerId,
                    clock.now().toEpochMilli(),
                    outcome.phase,
                    outcome.code,
                    outcome.retryable,
                )
            }
        }
        return outcome
    }
}
