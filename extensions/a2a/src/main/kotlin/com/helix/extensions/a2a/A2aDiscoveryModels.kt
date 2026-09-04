package com.helix.extensions.a2a

import com.helix.core.model.A2aAgentId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SecretAlias
import com.helix.core.model.Sha256

data class A2aAgentConfig(
    val id: A2aAgentId,
    val cardEndpoint: NormalizedEndpoint,
    val bearerSecretAlias: SecretAlias?,
    val enabled: Boolean,
) {
    init {
        require(cardEndpoint.scheme == "https" || cardEndpoint.isLiteralLoopback()) {
            "A2A Agent Card endpoint must use HTTPS"
        }
    }

    companion object {
        fun disabled(
            id: A2aAgentId,
            cardEndpoint: NormalizedEndpoint,
            bearerSecretAlias: SecretAlias? = null,
        ): A2aAgentConfig = A2aAgentConfig(id, cardEndpoint, bearerSecretAlias, enabled = false)
    }
}

enum class A2aBinding(
    val wireName: String,
) {
    JSON_RPC("JSONRPC"),
    HTTP_JSON("HTTP+JSON"),
}

data class A2aInterfaceSnapshot(
    val endpoint: NormalizedEndpoint,
    val binding: A2aBinding,
    val protocolVersion: String,
    val tenant: String?,
)

data class A2aProviderSnapshot(
    val organization: String,
    val url: String,
)

data class A2aCapabilitySnapshot(
    val streaming: Boolean,
    val pushNotifications: Boolean,
    val extendedAgentCard: Boolean,
)

data class A2aSkillSnapshot(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val inputModes: List<String>,
    val outputModes: List<String>,
    val contentHash: Sha256,
)

data class A2aAgentCardSnapshot(
    val agentId: A2aAgentId,
    val name: String,
    val description: String,
    val agentVersion: String,
    val provider: A2aProviderSnapshot?,
    val capabilities: A2aCapabilitySnapshot,
    val selectedInterface: A2aInterfaceSnapshot,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val skills: List<A2aSkillSnapshot>,
    val cardHash: Sha256,
    val canonicalCardJson: String,
    val extended: Boolean,
)

fun interface A2aCredentialLookup {
    fun lookup(alias: SecretAlias): String
}

internal fun NormalizedEndpoint.isLiteralLoopback(): Boolean =
    scheme == "http" && (host == "127.0.0.1" || host == "::1")
