package com.helix.provider.api

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import kotlinx.coroutines.flow.Flow

/**
 * Identity and static facts of one configured provider (provider doc section 2.1,
 * HXA-025). Derived from the validated [com.helix.provider.api.ProviderConfig]; no
 * credential material (only the [com.helix.core.model.SecretAlias] reference lives in the
 * config, never here).
 */
public data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val model: String,
    val endpoint: NormalizedEndpoint,
) {
    init {
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH) { "provider id must be 1..$MAX_ID_LENGTH chars" }
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be 1..$MAX_DISPLAY_NAME_LENGTH non-blank chars"
        }
        require(model.isNotBlank() && model.length <= MAX_MODEL_LENGTH) {
            "model must be 1..$MAX_MODEL_LENGTH non-blank chars"
        }
    }

    /** Data-destination class of the endpoint (provider doc section 2.5). */
    public val residence: ProviderResidence
        get() = endpoint.residence()

    internal companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_MODEL_LENGTH = 256
    }
}

/**
 * The internal unified provider contract (provider doc section 2.1, HXA-025): every network
 * protocol first converts into the internal [ModelRequest]/[ModelEvent] pair; the Agent
 * Loop never reads vendor JSON.
 *
 * The protocol is fixed by the [ProviderDescriptor.protocol] configuration — a failed
 * request never switches to another protocol (protocol island discipline,
 * [ProviderProtocol] KDoc).
 *
 * [stream] emits the internal event sequence and ends with exactly one terminal
 * ([ModelEvent.Completed]/[ModelEvent.Refusal]/[ModelEvent.Error]); transport-level
 * failures (non-2xx HTTP, DNS/TLS/timeout) are mapped to [ModelEvent.Error] with the
 * closed [com.helix.core.model.ModelErrorCode] classes — the stream contract stays total.
 */
public interface ModelProvider {
    public val descriptor: ProviderDescriptor

    /**
     * Phase 2 of the connection test (provider doc section 2.4): the model list when the
     * service exposes one; [ModelCatalogResult.Unsupported] otherwise (a service without
     * a list endpoint is not a failure).
     */
    public suspend fun listModels(): ModelCatalogResult

    /**
     * Phase 1 of the connection test (provider doc section 2.4): transport/TLS/HTTP
     * reachability and authentication.
     */
    public suspend fun validateConfiguration(): ProviderCheckResult

    /** One streaming model call. */
    public fun stream(request: ModelRequest): Flow<ModelEvent>
}
