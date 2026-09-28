package com.helix.provider.api.local

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.flow.Flow

/** Verified asset identity; paths and URLs are not supplied by model output. */
data class ModelAssetRef(
    val id: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(sizeBytes in 1..MAX_ASSET_BYTES)
    }

    companion object {
        const val MAX_ASSET_BYTES = 8L * 1024 * 1024 * 1024
    }
}

data class LocalModelInspection(
    val asset: ModelAssetRef,
    val metadata: ModelMetadata,
    val capabilities: ProviderCapabilities,
)

data class LocalModelLoadRequest(
    val asset: ModelAssetRef,
    val contextTokens: Int,
    val threads: Int,
) {
    init {
        require(contextTokens in 512..32768)
        require(threads in 1..8)
    }
}

data class LoadedLocalModel(
    val handle: String,
    val request: LocalModelLoadRequest,
)

data class LocalGenerationRequest(
    val generationId: String,
    val modelHandle: String,
    val request: ModelRequest,
) {
    init {
        require(generationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(modelHandle.isNotBlank() && modelHandle.length <= 128)
    }
}

enum class LocalCancelResult { REQUESTED, ACKNOWLEDGED, EXITED }

enum class LocalUnloadResult { UNLOADED, BUSY }

data class LocalRuntimeStatus(
    val loaded: LoadedLocalModel?,
    val activeGenerationId: String?,
)

/**
 * Framework-free runtime ownership boundary. Flow completion alone does not prove executor exit.
 * terminate must await runtime death and make its handles unusable before returning.
 */
interface LocalInferenceRuntimePort {
    suspend fun inspect(asset: ModelAssetRef): LocalModelInspection

    suspend fun load(request: LocalModelLoadRequest): LoadedLocalModel

    fun generate(request: LocalGenerationRequest): Flow<ModelEvent>

    suspend fun cancel(generationId: String): LocalCancelResult

    suspend fun unload(modelHandle: String): LocalUnloadResult

    suspend fun runtimeStatus(): LocalRuntimeStatus

    suspend fun terminate()
}

/** Closed diagnostics never carry prompts, paths, backend messages or credentials. */
class LocalRuntimeException(
    val code: ModelErrorCode,
) : Exception(code.name) {
    init {
        require(
            code in
                setOf(
                    ModelErrorCode.MODEL_LOAD_FAILED,
                    ModelErrorCode.MODEL_ASSET_INVALID,
                    ModelErrorCode.LOCAL_RUNTIME_CRASHED,
                    ModelErrorCode.LOCAL_RUNTIME_OOM,
                    ModelErrorCode.LOCAL_GENERATION_FAILED,
                    ModelErrorCode.LOCAL_CANCEL_TIMEOUT,
                ),
        )
    }
}
