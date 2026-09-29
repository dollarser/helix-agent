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
    private val gate: ProviderProbeGate,
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
    ): ProbeOutcome = runChecked(providerId, detectCapabilities)

    private suspend fun runChecked(
        providerId: String,
        detectCapabilities: Boolean,
    ): ProbeOutcome {
        val (token, snapshot) =
            gate.begin(providerId) {
                storage.providerConfigs.resolve(providerId) to storedConfig(providerId)
            }
        val config = snapshot.second
        val result = collectResult(config, detectCapabilities)
        val outcome = result.outcome
        var current = false
        gate.publish(providerId, token) {
            if (storage.providerConfigs.find(providerId) != snapshot.first) return@publish
            current = true
            when (outcome) {
                is ProbeOutcome.Ok -> {
                    publishSuccess(providerId, config, outcome, result)
                }

                is ProbeOutcome.Failed -> {
                    // Capability failures do not revoke a separately verified connection.
                    if (detectCapabilities) return@publish
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
        }
        return if (current) {
            outcome
        } else {
            ProbeOutcome.Failed(
                0,
                com.helix.core.model.ModelErrorCode.PROTOCOL,
                "PROBE_SUPERSEDED",
                false,
            )
        }
    }

    private data class Result(
        val outcome: ProbeOutcome,
        val metadata: Map<String, com.helix.provider.api.ModelMetadata> = emptyMap(),
        val window: Long? = null,
    )

    private suspend fun collectResult(
        config: ProviderConfig,
        detectCapabilities: Boolean,
    ): Result =
        try {
            val provider = factory.create(config)
            if (config.transport is com.helix.core.model.ProviderTransport.Network) onNetworkOperation()
            val outcome =
                run {
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
                }
            val metadata = if (outcome is ProbeOutcome.Ok) provider.modelMetadata() else emptyMap()
            val window = if (outcome is ProbeOutcome.Ok) provider.contextWindow(config.model) else null
            Result(outcome, metadata, window)
        } catch (failure: com.helix.provider.api.local.LocalRuntimeException) {
            Result(ProbeOutcome.Failed(1, failure.code, failure.code.name, false))
        }

    private suspend fun publishSuccess(
        providerId: String,
        config: ProviderConfig,
        outcome: ProbeOutcome.Ok,
        result: Result,
    ) {
        testStatus.modelMetadata.write(config.id, config.transport.cacheKey, result.metadata)
        // Reuse this provider's catalog instead of cold-binding and fetching it twice.
        discoverContextWindow(providerId, config.model, result.window)
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
}
