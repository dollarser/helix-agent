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
    ): ProbeOutcome =
        try {
            runChecked(providerId, detectCapabilities)
        } catch (failure: com.helix.provider.api.local.LocalRuntimeException) {
            if (!detectCapabilities) {
                testStatus.recordFailed(providerId, clock.now().toEpochMilli(), 1, failure.code, false)
            }
            ProbeOutcome.Failed(1, failure.code, failure.code.name, false)
        }

    private suspend fun runChecked(
        providerId: String,
        detectCapabilities: Boolean,
    ): ProbeOutcome {
        val config = storedConfig(providerId)
        val provider = factory.create(config)
        if (config.transport is com.helix.core.model.ProviderTransport.Network) onNetworkOperation()
        val outcome =
            if (detectCapabilities) {
                managed.probe(config, provider) ?: probe.probe(
                    provider,
                    includeVision = config.transport !is com.helix.core.model.ProviderTransport.OnDeviceLocal,
                )
            } else {
                ProviderConnectionCheck.run(
                    config,
                    provider,
                    (testStatus.statusFor(config.id) as? ConnectionTestStatus.Passed)?.capabilities,
                )
            }
        when (outcome) {
            is ProbeOutcome.Ok -> {
                testStatus.modelMetadata.write(config.id, config.transport.cacheKey, provider.modelMetadata())
                // Reuse this provider's catalog instead of cold-binding and fetching it twice.
                discoverContextWindow(providerId, config.model, provider.contextWindow(config.model))
                storage.providerConfigs.overwrite(
                    storage.providerConfigs.resolve(providerId).let { e ->
                        ProviderConfigSpec(
                            id = e.id,
                            displayName = e.displayName,
                            protocol = e.protocol?.let(ProviderProtocol::parse),
                            endpoint = e.endpoint,
                            model = e.model,
                            headersJson = e.headersJson,
                            secretAlias = e.secretAlias,
                            provisioningKind = e.provisioningKind,
                            transportKind = e.transportKind,
                            authKind = e.authKind,
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
                testStatus.modelMetadata.write(config.id, config.transport.cacheKey, emptyMap())
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
