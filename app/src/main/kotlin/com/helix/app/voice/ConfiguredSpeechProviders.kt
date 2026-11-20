package com.helix.app.voice

import com.helix.app.provider.configFrom
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpeechProviderOption(
    val id: String,
    val displayName: String,
    val asrEndpoint: String,
    val ttsEndpoint: String,
)

/** No key copies: selection stores only an ID and pinned destinations; credentials resolve per request. */
class ConfiguredSpeechProviders internal constructor(
    private val listConfigs: () -> List<ProviderConfig>,
    private val resolveConfig: (String) -> ProviderConfig,
    private val credentials: com.helix.provider.api.CredentialLookup,
) {
    constructor(storage: () -> HelixStorage) : this(
        { storage().providerConfigs.list().map(::configFrom) },
        { id -> configFrom(storage().providerConfigs.resolve(id)) },
        com.helix.provider.api
            .CredentialLookup { alias -> storage().secrets.get(alias) },
    )

    suspend fun available(): List<SpeechProviderOption> =
        withContext(Dispatchers.IO) {
            listConfigs().mapNotNull { config ->
                if (eligible(config)) option(config) else null
            }
        }

    fun wire(
        providerId: String,
        target: String,
        delegate: WireClient,
    ): WireClient =
        object : WireClient {
            override suspend fun open(request: WireRequest) =
                withContext(Dispatchers.IO) {
                    val config = resolveConfig(providerId)
                    require(eligible(config)) { "SPEECH_PROVIDER_UNAVAILABLE" }
                    val selected = option(config)
                    val normalized =
                        com.helix.core.model.NormalizedEndpoint
                            .parse(target)
                            .full
                    val destinations = listOf(selected.asrEndpoint, selected.ttsEndpoint)
                    require(normalized in destinations) { "SPEECH_PROVIDER_CHANGED" }
                    require(request.url == normalized) { "SPEECH_DESTINATION_CHANGED" }
                    val auth = config.auth as ProviderAuth.Secret
                    val key = credentials.lookup(auth.alias)
                    require(key.isNotBlank() && key.none { it == '\r' || it == '\n' })
                    // Account authentication headers are never reused by an API-Key-only invocation.
                    val requestHeaderNames =
                        request.headers.keys
                            .map { it.lowercase(java.util.Locale.ROOT) }
                            .toSet()
                    val headers =
                        config.headers.filterKeys {
                            it.lowercase(java.util.Locale.ROOT) !in requestHeaderNames &&
                                !it.equals("authorization", true) && !it.equals("x-client-id", true)
                        } +
                            request.headers.filterKeys {
                                !it.equals("authorization", true) && !it.equals("x-client-id", true)
                            } + ("Authorization" to "Bearer $key")
                    delegate.open(WireRequest(request.method, request.url, headers, request.body))
                }
        }

    private fun eligible(config: ProviderConfig): Boolean =
        config.provisioning == ProviderProvisioningKind.USER_CONFIGURED &&
            config.auth is ProviderAuth.Secret &&
            config.protocol in setOf(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderProtocol.OPENAI_RESPONSES)

    private fun option(config: ProviderConfig): SpeechProviderOption {
        val base = config.endpoint.full.trimEnd('/')
        return SpeechProviderOption(config.id, config.displayName, "$base/audio/transcriptions", "$base/audio/speech")
    }
}
