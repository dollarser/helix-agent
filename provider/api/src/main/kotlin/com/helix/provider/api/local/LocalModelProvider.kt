package com.helix.provider.api.local

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.ProviderProvisioningKind
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** No wire client, endpoint, credentials or special AgentLoop branch. */
class LocalModelProvider(
    private val config: ProviderConfig,
    private val asset: ModelAssetRef,
    private val runtime: LocalInferenceRuntimePort,
    private val loadRequest: LocalModelLoadRequest,
) : ModelProvider {
    init {
        require(config.provisioning == ProviderProvisioningKind.ON_DEVICE_ASSET)
        require(config.model == asset.id && loadRequest.asset == asset)
    }

    override val descriptor = ProviderDescriptor(config.id, config.displayName, config.connection, config.model)

    override suspend fun listModels(): ModelCatalogResult = ModelCatalogResult.Listed(listOf(asset.id))

    private suspend fun inspectConfigured(): LocalModelInspection {
        runtime.load(loadRequest)
        return runtime.inspect(asset)
    }

    override suspend fun modelMetadata() = mapOf(asset.id to inspectConfigured().metadata)

    override suspend fun contextWindow(model: String): Long? =
        if (model == asset.id) inspectConfigured().metadata.contextWindow else null

    override suspend fun validateConfiguration(): ProviderCheckResult =
        try {
            inspectConfigured()
            ProviderCheckResult.Ok
        } catch (failure: LocalRuntimeException) {
            ProviderCheckResult.Failed(failure.code, failure.code.name, false)
        }

    @Suppress("SwallowedException") // Failed cancellation requires process termination before publishing a terminal.
    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            require(request.model == asset.id) { "Local model selection changed" }
            val loaded = runtime.load(loadRequest)
            val generationId = UUID.randomUUID().toString()
            var terminal: ModelEvent? = null
            var invalid = false
            try {
                runtime.generate(LocalGenerationRequest(generationId, loaded.handle, request)).collect { event ->
                    if (terminal != null) {
                        invalid = true
                    } else if (event.isTerminal()) {
                        terminal = event
                    } else {
                        emit(event)
                    }
                }
            } finally {
                // Cancellation must wait for exit or terminate the private runtime; never release an unknown owner.
                withContext(NonCancellable) {
                    val exited =
                        try {
                            withTimeoutOrNull(CANCEL_EXIT_TIMEOUT_MS) {
                                runtime.cancel(generationId) == LocalCancelResult.EXITED
                            } ?: false
                        } catch (failure: LocalRuntimeException) {
                            false // A failed cancellation RPC cannot prove executor exit.
                        }
                    if (!exited) {
                        runtime.terminate()
                        terminal = ModelEvent.Error(ModelErrorCode.LOCAL_CANCEL_TIMEOUT, false)
                    }
                }
            }
            emit(if (invalid || terminal == null) ModelEvent.Error(ModelErrorCode.PROTOCOL, false) else terminal!!)
        }.catch { failure ->
            when (failure) {
                is LocalRuntimeException -> emit(ModelEvent.Error(failure.code, false))
                is IllegalArgumentException -> emit(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
                is java.io.IOException -> emit(ModelEvent.Error(ModelErrorCode.LOCAL_GENERATION_FAILED, false))
                else -> throw failure
            }
        }

    private fun ModelEvent.isTerminal() =
        this is ModelEvent.Completed || this is ModelEvent.Error || this is ModelEvent.Refusal

    private companion object {
        const val CANCEL_EXIT_TIMEOUT_MS = 5000L
    }
}
