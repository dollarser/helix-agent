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
    private val refreshAccount: suspend (String) -> ManagedAccountSnapshot? = { null },
) {
    /** The probe itself; only ever run on the service's IO scope (network + Room). */
    suspend fun run(
        providerId: String,
        detectCapabilities: Boolean = false,
        modelId: String? = null,
        verifyGeneration: Boolean = false,
    ): ProbeOutcome {
        val target = modelId ?: storedConfig(providerId).model
        ProviderSelectedModels.validate(listOf(target))
        return runChecked(providerId, detectCapabilities, target, verifyGeneration)
    }

    private suspend fun runChecked(
        providerId: String,
        detectCapabilities: Boolean,
        modelId: String,
        verifyGeneration: Boolean,
    ): ProbeOutcome {
        val account = refreshAccount(providerId)
        if (account != null && !account.ready) {
            return ProbeOutcome.Failed(1, com.helix.core.model.ModelErrorCode.AUTH, "ACCOUNT_NOT_READY", false)
        }
        val (token, snapshot) =
            gate.begin(providerId, if (detectCapabilities) "capabilities:$modelId" else "connection") {
                storage.providerConfigs.resolve(providerId) to storedConfig(providerId)
            }
        val config = snapshot.second.copy(model = modelId)
        if (config.transport is com.helix.core.model.ProviderTransport.OnDeviceLocal) {
            require(modelId == snapshot.second.model)
        }
        val verification = testStatus.modelEvidence.read(providerId, config.transport.cacheKey).verifications[modelId]
        val previous =
            verification?.capabilities ?: if (modelId == snapshot.second.model && verification == null) {
                (testStatus.statusFor(providerId) as? ConnectionTestStatus.Passed)?.capabilities
            } else {
                null
            }
        val result = collectResult(config, detectCapabilities, verifyGeneration)
        val outcome = result.outcome
        val sameAccount = refreshAccount(providerId) == account
        var current = false
        gate.publish(providerId, token) {
            // A different successful probe may refresh capabilities without changing the endpoint/configuration.
            if (!sameAccount ||
                storage.providerConfigs.find(providerId)?.copy(capabilitySnapshot = snapshot.first.capabilitySnapshot)
                != snapshot.first
            ) {
                return@publish
            }
            current = true
            publishOutcome(config, result, detectCapabilities, previous)
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

    private suspend fun publishOutcome(
        config: ProviderConfig,
        result: Result,
        detectCapabilities: Boolean,
        previous: ProviderCapabilities?,
    ) {
        when (val outcome = result.outcome) {
            is ProbeOutcome.Ok -> {
                val exact =
                    testStatus.modelEvidence
                        .read(config.id, config.transport.cacheKey)
                        .verifications[config.model]
                // An independently published failure must not resurrect an older successful capability test.
                val latest =
                    if (exact != null) {
                        exact.capabilities ?: outcome.capabilities
                    } else {
                        previous ?: outcome.capabilities
                    }
                val caps = ProviderProbePublication.capabilities(detectCapabilities, outcome.capabilities, latest)
                publishSuccess(config.id, config, outcome.copy(capabilities = caps), result, detectCapabilities)
            }

            is ProbeOutcome.Failed -> {
                publishFailure(config, result, outcome, detectCapabilities)
            }
        }
    }

    private fun publishFailure(
        config: ProviderConfig,
        result: Result,
        outcome: ProbeOutcome.Failed,
        detectCapabilities: Boolean,
    ) {
        val failure = ProviderModelVerification(clock.now().toEpochMilli(), failure = outcome.code)
        if (detectCapabilities) {
            testStatus.modelEvidence.verify(config.id, config.transport.cacheKey, config.model, failure)
        } else if (result.generationAttempted) {
            testStatus.modelEvidence.generation(config.id, config.transport.cacheKey, config.model, failure)
        }
        if (result.sourceFailure) {
            testStatus.recordFailed(
                config.id,
                clock.now().toEpochMilli(),
                outcome.phase,
                outcome.code,
                outcome.retryable,
            )
        }
    }

    private data class Result(
        val outcome: ProbeOutcome,
        val metadata: Map<String, com.helix.provider.api.ModelMetadata> = emptyMap(),
        val window: Long? = null,
        val generationAttempted: Boolean = false,
        val sourceFailure: Boolean = false,
    )

    private suspend fun collectResult(
        config: ProviderConfig,
        detectCapabilities: Boolean,
        verifyGeneration: Boolean,
    ): Result =
        try {
            val provider = factory.create(config)
            if (config.transport is com.helix.core.model.ProviderTransport.Network) onNetworkOperation()
            val connection =
                if (!detectCapabilities) {
                    ProviderConnectionCheck.inspect(config, provider, null, verifyGeneration)
                } else {
                    null
                }
            val outcome =
                connection?.outcome ?: managed.probe(config, provider) ?: probe.probe(
                    provider,
                    includeVision = config.transport !is com.helix.core.model.ProviderTransport.OnDeviceLocal,
                )
            val metadata = if (outcome is ProbeOutcome.Ok) provider.modelMetadata() else emptyMap()
            val window = if (outcome is ProbeOutcome.Ok) provider.contextWindow(config.model) else null
            Result(
                outcome,
                metadata,
                window,
                connection?.generationAttempted == true,
                connection?.sourceFailure == true ||
                    (outcome is ProbeOutcome.Failed && outcome.code == com.helix.core.model.ModelErrorCode.AUTH),
            )
        } catch (failure: com.helix.provider.api.local.LocalRuntimeException) {
            Result(ProbeOutcome.Failed(1, failure.code, failure.code.name, false))
        }

    private fun publishModelEvidence(
        config: ProviderConfig,
        outcome: ProbeOutcome.Ok,
        generationAttempted: Boolean,
        detectCapabilities: Boolean,
    ) {
        val observation = ProviderModelVerification(clock.now().toEpochMilli(), outcome.capabilities)
        if (detectCapabilities) {
            testStatus.modelEvidence.verify(config.id, config.transport.cacheKey, config.model, observation)
        }
        if (generationAttempted || detectCapabilities) {
            testStatus.modelEvidence.generation(config.id, config.transport.cacheKey, config.model, observation)
        }
    }

    private suspend fun publishSuccess(
        providerId: String,
        config: ProviderConfig,
        outcome: ProbeOutcome.Ok,
        result: Result,
        detectCapabilities: Boolean,
    ) {
        val identity = config.transport.cacheKey
        val current = storage.providerConfigs.resolve(providerId)
        publishModelEvidence(config, outcome, result.generationAttempted, detectCapabilities)
        if (!detectCapabilities) outcome.models?.let { testStatus.modelEvidence.catalog(providerId, identity, it) }
        if (result.metadata.isNotEmpty()) {
            testStatus.modelMetadata.write(config.id, identity, result.metadata)
        }
        if (config.model != current.model) {
            discoverContextWindow(providerId, config.model, result.window)
            if (!detectCapabilities || testStatus.statusFor(providerId) !is ConnectionTestStatus.Passed) {
                testStatus.recordPassed(
                    providerId,
                    clock.now().toEpochMilli(),
                    ProviderCapabilities.parse(current.capabilitySnapshot),
                    testStatus.modelEvidence.read(providerId, identity).catalog,
                )
            }
            return
        }
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
