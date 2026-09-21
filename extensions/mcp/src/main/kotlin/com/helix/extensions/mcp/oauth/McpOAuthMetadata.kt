package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint
import com.helix.extensions.mcp.McpEndpointGate
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
    @SerialName("device_authorization_endpoint")
    val deviceAuthorizationEndpoint: String? = null,
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

    /** Resource metadata selects the issuer; each response is bounded and each origin passes the existing gate. */
    suspend fun discover(baseEndpoint: NormalizedEndpoint): McpOAuthServerMetadata =
        withContext(Dispatchers.IO) {
            val resourcePaths =
                listOf(
                    "/.well-known/oauth-protected-resource" + baseEndpoint.path,
                    "/.well-known/oauth-protected-resource",
                ).distinct()
            var issuer: String? = null
            for (path in resourcePaths) {
                val body = fetch(baseEndpoint.origin + path)
                if (body != null) {
                    val root = json.parseToJsonElement(body).jsonObject
                    val resource = requireNotNull(root["resource"]?.jsonPrimitive?.content)
                    require(NormalizedEndpoint.parse(resource).full == baseEndpoint.full) {
                        "OAuth resource mismatch"
                    }
                    issuer =
                        root["authorization_servers"]
                            ?.jsonArray
                            ?.firstOrNull()
                            ?.jsonPrimitive
                            ?.content
                    require(!issuer.isNullOrBlank()) { "Missing authorization server" }
                    break
                }
            }
            val expectedIssuer = NormalizedEndpoint.parse(issuer ?: baseEndpoint.origin)
            val candidates =
                listOf(
                    expectedIssuer.origin + "/.well-known/oauth-authorization-server" + expectedIssuer.path,
                    expectedIssuer.full.trimEnd('/') + "/.well-known/openid-configuration",
                ).distinct()
            var metadata: McpOAuthServerMetadata? = null
            for (url in candidates) {
                val body = fetch(url)
                if (body != null) {
                    metadata = parseMetadata(body)
                    require(NormalizedEndpoint.parse(metadata.issuer).full == expectedIssuer.full) {
                        "OAuth issuer mismatch"
                    }
                    require(metadata.supportsS256()) { "PKCE S256 unsupported" }
                    val endpoints =
                        listOfNotNull(
                            metadata.authorizationEndpoint,
                            metadata.tokenEndpoint,
                            metadata.revocationEndpoint,
                            metadata.deviceAuthorizationEndpoint,
                        )
                    endpoints.forEach { endpoint ->
                        endpointGate.authorize(NormalizedEndpoint.parse(endpoint))
                    }
                    break
                }
            }
            requireNotNull(metadata) { "OAuth metadata unavailable" }
        }

    private suspend fun fetch(url: String): String? {
        val endpoint = NormalizedEndpoint.parse(url)
        val permit = endpointGate.authorize(endpoint)
        val client =
            okHttpClient
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(Proxy.NO_PROXY)
                .dns(
                    Dns { hostname ->
                        if (hostname != permit.host) throw UnknownHostException("Unexpected OAuth metadata host")
                        permit.pinnedAddresses(hostname)
                    },
                ).build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
        return client.newCall(request).oauthResponse().use { response ->
            if (response.code == 404) {
                null
            } else {
                require(response.isSuccessful) { "OAuth metadata HTTP ${response.code}" }
                response.body.oauthText(MAX_METADATA_BYTES)
            }
        }
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
            deviceAuthorizationEndpoint = root["device_authorization_endpoint"]?.jsonPrimitive?.content,
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
