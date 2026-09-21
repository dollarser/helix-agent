package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Duration

@Serializable
data class McpOAuthServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String,
    @SerialName("revocation_endpoint")
    val revocationEndpoint: String? = null,
    @SerialName("registration_endpoint")
    val registrationEndpoint: String? = null,
    @SerialName("scopes_supported")
    val scopesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String> = emptyList(),
) {
    init {
        require(issuer.isNotBlank()) { "issuer must not be blank" }
        require(authorizationEndpoint.isNotBlank()) { "authorizationEndpoint must not be blank" }
        require(tokenEndpoint.isNotBlank()) { "tokenEndpoint must not be blank" }
    }

    fun supportsS256(): Boolean = codeChallengeMethodsSupported.isEmpty() || "S256" in codeChallengeMethodsSupported
}

class McpOAuthDiscovery(
    private val endpointGate: McpEndpointGate,
    private val okHttpClient: OkHttpClient = defaultOAuthHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Discovers authorization server metadata per RFC 8414.
     * Checks endpoint authorization via [endpointGate] to enforce SSRF policy.
     */
    suspend fun discover(baseEndpoint: NormalizedEndpoint): McpOAuthServerMetadata =
        withContext(Dispatchers.IO) {
            val permit = endpointGate.authorize(baseEndpoint)
            val client =
                okHttpClient
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .proxy(Proxy.NO_PROXY)
                    .dns(
                        Dns { hostname ->
                            if (hostname != permit.host) {
                                throw UnknownHostException("Unexpected host in OAuth discovery")
                            }
                            permit.pinnedAddresses(hostname)
                        },
                    ).build()

            // Try RFC 8414 /.well-known/oauth-authorization-server,
            // fallback to OpenID /.well-known/openid-configuration
            val candidatePaths =
                listOf(
                    "/.well-known/oauth-authorization-server",
                    "/.well-known/openid-configuration",
                )

            var lastError: Exception? = null
            for (path in candidatePaths) {
                val discoveryUrl = "${baseEndpoint.origin}$path"
                val request =
                    Request
                        .Builder()
                        .url(discoveryUrl)
                        .header("Accept", "application/json")
                        .get()
                        .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body.string()
                            require(body.length <= MAX_METADATA_BYTES) {
                                "Discovery metadata exceeds max bytes"
                            }
                            return@withContext parseMetadata(body)
                        }
                    }
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw IOException(
                "Failed to discover OAuth metadata for ${baseEndpoint.origin}: ${lastError?.message}",
                lastError,
            )
        }

    private fun parseMetadata(jsonStr: String): McpOAuthServerMetadata {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val issuer = requireNotNull(root["issuer"]?.jsonPrimitive?.content) { "Missing issuer" }
        val authEp =
            requireNotNull(root["authorization_endpoint"]?.jsonPrimitive?.content) {
                "Missing authorization_endpoint"
            }
        val tokenEp =
            requireNotNull(root["token_endpoint"]?.jsonPrimitive?.content) {
                "Missing token_endpoint"
            }
        val revokeEp = root["revocation_endpoint"]?.jsonPrimitive?.content
        val regEp = root["registration_endpoint"]?.jsonPrimitive?.content
        val scopes =
            root["scopes_supported"]?.jsonArray?.map {
                it.jsonPrimitive.content
            } ?: emptyList()
        val methods =
            root["code_challenge_methods_supported"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()

        return McpOAuthServerMetadata(
            issuer = issuer,
            authorizationEndpoint = authEp,
            tokenEndpoint = tokenEp,
            revocationEndpoint = revokeEp,
            registrationEndpoint = regEp,
            scopesSupported = scopes,
            codeChallengeMethodsSupported = methods,
        )
    }

    companion object {
        const val MAX_METADATA_BYTES = 65_536

        fun defaultOAuthHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .build()
    }
}
