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
    private val discoverContextWindow: suspend (String, String, Long?) -> Unit,
    private val onNetworkOperation: () -> Unit,
) {
    /** The probe itself; only ever run on the service's IO scope (network + Room). */
    suspend fun run(
        providerId: String,
        detectCapabilities: Boolean = false,
    ): ProbeOutcome {
        val config = storedConfig(providerId)
        val provider = factory.create(config)
        onNetworkOperation()
        val outcome =
            if (detectCapabilities) {
                managed.probe(config, provider) ?: probe.probe(provider)
            } else {
                ProviderConnectionCheck.run(
                    config,
                    provider,
                    (testStatus.statusFor(config.id) as? ConnectionTestStatus.Passed)?.capabilities,
                )
            }
        when (outcome) {
            is ProbeOutcome.Ok -> {
                testStatus.modelMetadata.write(config.id, config.endpoint.full, provider.modelMetadata())
                // Reuse this provider's catalog instead of cold-binding and fetching it twice.
                discoverContextWindow(providerId, config.model, provider.contextWindow(config.model))
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
                // Capability failures do not revoke a separately verified connection.
                if (detectCapabilities) return outcome
                testStatus.modelMetadata.write(config.id, config.endpoint.full, emptyMap())
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
