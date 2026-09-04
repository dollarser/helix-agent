package com.helix.extensions.mcp

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SecretAlias
import kotlinx.serialization.json.JsonObject

data class McpServerConfig(
    val id: McpServerId,
    val endpoint: NormalizedEndpoint,
    val bearerSecretAlias: SecretAlias? = null,
    val enabled: Boolean = false,
) {
    init {
        val residence = endpoint.residence()
        require(
            endpoint.scheme == "https" ||
                residence == ProviderResidence.ON_DEVICE_LOOPBACK ||
                residence == ProviderResidence.USER_AUTHORIZED_LAN,
        ) {
            "cleartext MCP is limited to loopback or LAN endpoints"
        }
    }

    companion object {
        fun disabled(
            id: String,
            endpointUrl: String,
            bearerSecretAlias: String? = null,
        ): McpServerConfig =
            McpServerConfig(
                id = McpServerId(id),
                endpoint = NormalizedEndpoint.parse(endpointUrl),
                bearerSecretAlias = bearerSecretAlias?.let(::SecretAlias),
            )
    }
}

fun interface McpCredentialLookup {
    /** Resolves an alias at connection-test time. Missing/corrupt credentials must throw. */
    fun lookup(alias: SecretAlias): String
}

fun interface McpEndpointGate {
    /** Must fail closed unless the current profile, scope and complete DNS candidate set are allowed. */
    suspend fun authorize(endpoint: NormalizedEndpoint): McpNetworkPermit
}

data class McpHandshakeSnapshot(
    val serverId: McpServerId,
    val endpoint: String,
    val origin: String,
    val residence: ProviderResidence,
    val identity: McpServerIdentity,
    val metadata: McpMetadataSnapshot,
)

class McpHandshakeService(
    private val credentials: McpCredentialLookup,
    private val endpointGate: McpEndpointGate,
    private val clientName: String,
    private val clientVersion: String,
) {
    init {
        require(clientName.isNotBlank()) { "clientName must not be blank" }
        require(clientVersion.isNotBlank()) { "clientVersion must not be blank" }
    }

    /**
     * Tests a saved configuration without enabling it. The bearer value exists only for the
     * lifetime of this call and is never copied into the returned snapshot.
     */
    suspend fun testConnection(
        config: McpServerConfig,
        limits: McpMetadataLimits = McpMetadataLimits(),
    ): McpHandshakeSnapshot {
        val networkPermit = endpointGate.authorize(config.endpoint)
        val bearer =
            config.bearerSecretAlias?.let { alias ->
                credentials.lookup(alias).also(::requireBearerCredential)
            }
        val facade =
            SdkMcpClientFacade(
                clientName = clientName,
                clientVersion = clientVersion,
                authenticatedEndpoint = config.endpoint.takeIf { bearer != null },
                bearerToken = bearer,
                networkPermit = networkPermit,
            )
        val session = facade.connect(config.endpoint.full)
        try {
            return McpHandshakeSnapshot(
                serverId = config.id,
                endpoint = config.endpoint.full,
                origin = config.endpoint.origin,
                residence = config.endpoint.residence(),
                identity = session.server,
                metadata = session.snapshotMetadata(limits),
            )
        } finally {
            session.close()
        }
    }
}

internal fun requireBearerCredential(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.isNotEmpty()) { "MCP bearer credential must not be empty" }
    require(bytes.size <= MAX_BEARER_BYTES) { "MCP bearer credential exceeds $MAX_BEARER_BYTES bytes" }
    require(value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~+/=" }) {
        "MCP bearer credential contains invalid characters"
    }
}

private const val MAX_BEARER_BYTES = 4_096

data class McpMetadataLimits(
    val maxItemsPerKind: Int = 128,
    val maxTextChars: Int = 4_096,
    val maxSchemaBytes: Int = 65_536,
    val maxPromptArguments: Int = 64,
    val maxTotalMetadataBytes: Int = 524_288,
) {
    init {
        require(maxItemsPerKind in 1..1_024) { "maxItemsPerKind must be 1..1024" }
        require(maxTextChars in 1..65_536) { "maxTextChars must be 1..65536" }
        require(maxSchemaBytes in 1..1_048_576) { "maxSchemaBytes must be 1..1048576" }
        require(maxPromptArguments in 1..1_024) { "maxPromptArguments must be 1..1024" }
        require(maxTotalMetadataBytes in 1..8_388_608) { "maxTotalMetadataBytes must be 1..8388608" }
    }
}

data class McpMetadataSnapshot(
    val capabilities: McpCapabilitySnapshot,
    val tools: List<McpToolMetadata>,
    val resources: List<McpResourceMetadata>,
    val prompts: List<McpPromptMetadata>,
    val toolsHaveMore: Boolean,
    val resourcesHaveMore: Boolean,
    val promptsHaveMore: Boolean,
)

data class McpCapabilitySnapshot(
    val tools: Boolean,
    val resources: Boolean,
    val prompts: Boolean,
    val toolListChanged: Boolean,
    val resourceListChanged: Boolean,
    val resourceSubscribe: Boolean,
    val promptListChanged: Boolean,
)

data class McpToolMetadata(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject?,
    val schemaHash: String,
    val serverProvidedHints: Map<String, Boolean>,
)

data class McpResourceMetadata(
    val uri: String,
    val name: String,
    val title: String?,
    val description: String?,
    val mimeType: String?,
    val size: Long?,
    val metadataHash: String,
)

data class McpPromptMetadata(
    val name: String,
    val title: String?,
    val description: String?,
    val arguments: List<McpPromptArgumentMetadata>,
    val argumentsTruncated: Boolean,
    val metadataHash: String,
)

data class McpPromptArgumentMetadata(
    val name: String,
    val description: String?,
    val required: Boolean,
)
