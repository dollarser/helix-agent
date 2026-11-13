package com.helix.app.provider

import com.helix.core.model.Clock
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderContextSettings

/** Per-model context operations preserve the configuration and account identity of each request. */
@Suppress("LongParameterList")
internal class ProviderContextOperations(
    private val factory: ProviderFactory,
    private val testStatus: ProviderTestStatusStore,
    private val contextSettingsStore: ProviderContextSettingsStore,
    private val clock: Clock,
    private val probeGate: ProviderProbeGate,
    private val storedConfig: suspend (String) -> ProviderConfig,
    private val managedAccountFor: suspend (String) -> ManagedAccountSnapshot?,
    private val metadataFor: (String, String) -> ModelMetadata?,
    private val changed: () -> Unit,
    private val refresh: () -> Unit,
) {
    private val contextWindowProbes = ContextWindowProbeCache()

    suspend fun load(
        providerId: String,
        model: String? = null,
        expectedConfig: ProviderConfig? = null,
    ): ProviderContextSettings {
        val config = storedConfig(providerId)
        if (expectedConfig != null && !sameProviderEditableConfig(config, expectedConfig)) {
            throw ProviderDraftSaveConflictException()
        }
        val selectedModel = model ?: config.model
        val stored =
            testStatus.modelMetadata
                .contextSettings(providerId, config.transport.cacheKey, selectedModel, contextSettingsStore)
        val detected = metadataFor(providerId, selectedModel)?.contextWindow
        val missingWindow = stored.serverWindow == null && detected == null
        val mayDiscover = BuiltInModel.owns(providerId) && testStatus.builtInCatalog.accountAvailable()
        if (missingWindow && mayDiscover) {
            val key = testStatus.builtInCatalog.identity() + listOf(config.transport.cacheKey, selectedModel)
            if (contextWindowProbes.due(key, clock.now().toEpochMilli())) {
                return discover(providerId, selectedModel, expectedConfig)
            }
        }
        return stored.withDetectedWindow(detected)
    }

    suspend fun save(
        providerId: String,
        model: String,
        settings: ProviderContextSettings,
        expectedConfig: ProviderConfig? = null,
    ) = probeGate.mutate(providerId) {
        val config = storedConfig(providerId)
        if (expectedConfig != null && !sameProviderEditableConfig(config, expectedConfig)) {
            throw ProviderDraftSaveConflictException()
        }
        val local = config.transport is com.helix.core.model.ProviderTransport.OnDeviceLocal
        if (local) {
            require(settings.manualWindow == null || settings.manualWindow in 1024L..32768L)
            testStatus.modelEvidence.verify(
                providerId,
                config.transport.cacheKey,
                model,
                ProviderModelVerification(clock.now().toEpochMilli()),
            )
            testStatus.modelMetadata.write(config.id, config.transport.cacheKey, emptyMap())
        }
        val previous = contextSettingsStore.read(providerId, config.transport.cacheKey, model)
        contextSettingsStore.write(
            providerId,
            config.transport.cacheKey,
            model,
            if (local) {
                settings.copy(serverWindow = if (settings.manualWindow == null) 4096L else null)
            } else {
                settings.copy(serverWindow = previous.serverWindow)
            },
        )
        changed()
        refresh()
    }

    suspend fun discover(
        providerId: String,
        model: String,
        expectedConfig: ProviderConfig? = null,
    ): ProviderContextSettings =
        run {
            val account = managedAccountFor(providerId)
            check(account == null || account.ready) { "Account is not ready" }
            val (token, config) =
                probeGate.begin(providerId, "context:$model") {
                    storedConfig(providerId).also {
                        if (expectedConfig != null && !sameProviderEditableConfig(it, expectedConfig)) {
                            throw ProviderDraftSaveConflictException()
                        }
                    }
                }
            val detected = factory.create(config.copy(model = model)).contextWindow(model)
            val sameAccount = managedAccountFor(providerId) == account
            probeGate.publish(providerId, token) {
                if (sameAccount &&
                    storedConfig(providerId).copy(capabilitySnapshot = config.capabilitySnapshot) == config
                ) {
                    val previous = contextSettingsStore.read(providerId, config.transport.cacheKey, model)
                    contextSettingsStore.write(
                        providerId,
                        config.transport.cacheKey,
                        model,
                        previous.copy(serverWindow = detected),
                    )
                    changed()
                }
            }
            // Do not re-enter discovery if an optional metadata request took longer than the miss-cache interval.
            val current = storedConfig(providerId)
            if (expectedConfig != null && !sameProviderEditableConfig(current, expectedConfig)) {
                throw ProviderDraftSaveConflictException()
            }
            testStatus.modelMetadata
                .contextSettings(providerId, current.transport.cacheKey, model, contextSettingsStore)
                .withDetectedWindow(metadataFor(providerId, model)?.contextWindow)
        }
}
