package com.helix.core.storage.repository

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SecretAlias
import com.helix.core.storage.entity.McpCapabilityEntity
import com.helix.core.storage.entity.McpServerEntity
import com.helix.core.storage.entity.ProviderConfigEntity

/**
 * Provider configuration input (doc 9.1 `provider_configs` row fields). [protocol] is the
 * closed [ProviderProtocol] enum; [endpoint] is a raw URL fully validated (and stored in
 * canonical normalized form); [headersJson] must pass the [ProviderHeaders] allowlist;
 * [secretAlias] must be a legal [SecretAlias] (the credential itself lives only in the
 * SecretStore — no plaintext column, architecture/overview §9.1 and
 * security/testing-and-release).
 */
data class ProviderConfigSpec(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val endpoint: String,
    val model: String,
    val headersJson: String,
    val secretAlias: String,
    val capabilitySnapshot: String,
) {
    /** Validated, canonical row shared by save/overwrite; fails closed on any violation. */
    internal fun toEntity(): ProviderConfigEntity {
        ProviderId(id)
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be 1..$MAX_DISPLAY_NAME_LENGTH non-blank chars"
        }
        val normalized = NormalizedEndpoint.parse(endpoint)
        require(model.isNotBlank() && model.length <= MAX_MODEL_LENGTH) {
            "model must be 1..$MAX_MODEL_LENGTH non-blank chars"
        }
        require(model.none { it <= ' ' || it == '\u007F' }) { "model contains control characters" }
        val headers = ProviderHeaders.parse(headersJson)
        SecretAlias(secretAlias)
        require(capabilitySnapshot.isNotBlank()) { "capabilitySnapshot must not be blank" }
        return ProviderConfigEntity(
            id,
            displayName,
            protocol.name,
            normalized.full,
            model,
            ProviderHeaders.toStorageString(headers),
            secretAlias,
            capabilitySnapshot,
        )
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_MODEL_LENGTH = 256
    }
}

data class McpHttpServerSpec(
    val id: String,
    val endpoint: String,
    val authAlias: String?,
) {
    internal fun toEntity(): McpServerEntity {
        McpServerId(id)
        val normalized = NormalizedEndpoint.parse(endpoint)
        val residence = normalized.residence()
        require(
            normalized.scheme == "https" ||
                residence == ProviderResidence.ON_DEVICE_LOOPBACK ||
                residence == ProviderResidence.USER_AUTHORIZED_LAN,
        ) {
            "cleartext MCP is limited to loopback or LAN endpoints"
        }
        authAlias?.let(::SecretAlias)
        return McpServerEntity(
            id = id,
            transport = MCP_STREAMABLE_HTTP,
            endpointRef = normalized.full,
            commandRef = null,
            authAlias = authAlias,
            enabled = false,
            trustState = MCP_UNTRUSTED,
        )
    }
}

data class McpCapabilitySpec(
    val protocolVersion: String,
    val kind: McpCapabilityKind,
    val name: String,
    val contentHash: String,
) {
    init {
        require(protocolVersion.isNotBlank() && protocolVersion.length <= 64) {
            "protocolVersion must be 1..64 non-blank characters"
        }
        require(name.isNotBlank() && name.length <= 4_096) { "name must be 1..4096 non-blank characters" }
        require(contentHash.length == 64 && contentHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "contentHash must be lowercase sha256 hex"
        }
    }

    internal fun toEntity(
        serverId: String,
        enabled: Boolean,
    ): McpCapabilityEntity =
        McpCapabilityEntity(
            rowId = 0,
            serverId = serverId,
            protocolVersion = protocolVersion,
            kind = kind.storageValue,
            name = name,
            schemaHash = contentHash,
            enabled = enabled,
        )
}

enum class McpCapabilityKind(
    internal val storageValue: String,
) {
    CAPABILITY("capability"),
    TOOL("tool"),
    RESOURCE("resource"),
    PROMPT("prompt"),
}

private const val MCP_STREAMABLE_HTTP = "streamable-http"
private const val MCP_UNTRUSTED = "UNTRUSTED"
internal const val MAX_MCP_CAPABILITIES = 3_072
