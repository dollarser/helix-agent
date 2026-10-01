package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint
import com.helix.extensions.mcp.McpEndpointGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration

/** Public client identity, not a token or a permission grant. */
data class McpOAuthClientIdentity(
    val issuer: String,
    val redirectUri: String,
    val clientId: String,
    val source: String,
) {
    init {
        NormalizedEndpoint.parse(issuer)
        require(clientId.isNotBlank() && clientId.length <= 2048 && clientId.none { it.isISOControl() })
        require(redirectUri.isNotBlank() && redirectUri.length <= 2048)
        require(source in setOf("preregistered", "cimd", "dynamic"))
    }
}

/** Explicit public-client setup; discovery alone never performs dynamic registration. */
class McpOAuthClientRegistration(
    private val endpointGate: McpEndpointGate,
    private val http: OkHttpClient = McpOAuthClient.defaultHttpClient(),
) {
    suspend fun validateDocument(
        metadata: McpOAuthServerMetadata,
        documentUrl: String,
        redirectUri: String,
    ): McpOAuthClientIdentity =
        withContext(Dispatchers.IO) {
            require(metadata.clientIdMetadataDocumentSupported) { "OAUTH_CIMD_NOT_SUPPORTED" }
            val url = URI(documentUrl)
            require(
                url.scheme == "https" && !url.rawPath.isNullOrBlank() && url.rawPath != "/",
            ) { "OAUTH_CIMD_URL_INVALID" }
            NormalizedEndpoint.parse(documentUrl)
            val root = request(documentUrl, null)
            require(root.string("client_id") == documentUrl) { "OAUTH_CIMD_ID_MISMATCH" }
            require(root.string("client_name").isNotBlank()) { "OAUTH_CIMD_NAME_MISSING" }
            validatePublicMetadata(root, redirectUri)
            McpOAuthClientIdentity(metadata.issuer, redirectUri, documentUrl, "cimd")
        }

    suspend fun register(
        metadata: McpOAuthServerMetadata,
        redirectUri: String,
    ): McpOAuthClientIdentity =
        withContext(Dispatchers.IO) {
            require(!metadata.clientIdMetadataDocumentSupported) { "OAUTH_CIMD_PREFERRED" }
            require(metadata.supportsS256()) { "OAUTH_S256_REQUIRED" }
            require(redirectUri.isNotBlank() && redirectUri.length <= 2048 && URI(redirectUri).scheme != null)
            val endpoint = requireNotNull(metadata.registrationEndpoint) { "OAUTH_REGISTRATION_NOT_SUPPORTED" }
            val body =
                buildJsonObject {
                    put("client_name", "Helix")
                    put("application_type", "native")
                    put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
                    put("grant_types", JsonArray(listOf("authorization_code", "refresh_token").map(::JsonPrimitive)))
                    put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                    put("token_endpoint_auth_method", "none")
                }.toString()
            val root = request(endpoint, body)
            validatePublicMetadata(root, redirectUri)
            McpOAuthClientIdentity(metadata.issuer, redirectUri, root.string("client_id"), "dynamic")
        }

    private suspend fun request(
        url: String,
        body: String?,
    ): JsonObject {
        val permit = endpointGate.authorize(NormalizedEndpoint.parse(url))
        val client =
            http
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .proxy(Proxy.NO_PROXY)
                .callTimeout(Duration.ofSeconds(20))
                .dns(
                    Dns { hostname ->
                        if (hostname != permit.host) throw UnknownHostException("Unexpected OAuth registration host")
                        permit.pinnedAddresses(hostname)
                    },
                ).build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { if (body != null) post(body.toRequestBody("application/json; charset=utf-8".toMediaType())) }
                .build()
        return client.newCall(request).oauthResponse().use { response ->
            require(response.isSuccessful) { "OAUTH_CLIENT_SETUP_HTTP_${response.code}" }
            Json.parseToJsonElement(response.body.oauthText(McpOAuthClient.MAX_TOKEN_RESPONSE_BYTES)).jsonObject
        }
    }

    private fun validatePublicMetadata(
        root: JsonObject,
        redirectUri: String,
    ) {
        require("client_secret" !in root) { "OAUTH_CONFIDENTIAL_CLIENT_UNSUPPORTED" }
        require(root.string("token_endpoint_auth_method") == "none") { "OAUTH_PUBLIC_CLIENT_REQUIRED" }
        require(redirectUri in root.strings("redirect_uris")) { "OAUTH_REDIRECT_NOT_REGISTERED" }
        root["response_types"]?.let { require(root.strings("response_types") == listOf("code")) }
        root["grant_types"]?.let {
            val grants = root.strings("grant_types")
            require("authorization_code" in grants && grants.all { it in setOf("authorization_code", "refresh_token") })
        }
    }

    private fun JsonObject.string(name: String): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString && value.content.length <= 2048 && value.content.none { it.isISOControl() })
        return value.content
    }

    private fun JsonObject.strings(name: String): List<String> {
        val values = getValue(name).jsonArray
        require(values.isNotEmpty() && values.size <= 16)
        return values
            .map { value ->
                val text = value.jsonPrimitive
                require(text.isString && text.content.length <= 2048 && text.content.none { it.isISOControl() })
                text.content
            }.also { require(it.distinct().size == it.size) }
    }
}
