package com.helix.extensions.a2a

import com.helix.core.model.NormalizedEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface A2aAgentCardClient {
    suspend fun fetchPublic(cardEndpoint: NormalizedEndpoint): String

    suspend fun fetchExtended(
        selectedInterface: A2aInterfaceSnapshot,
        bearer: String,
    ): String
}

internal class OkHttpA2aAgentCardClient(
    private val client: OkHttpClient,
) : A2aAgentCardClient {
    override suspend fun fetchPublic(cardEndpoint: NormalizedEndpoint): String =
        execute(
            Request
                .Builder()
                .url(cardEndpoint.full)
                .get()
                .build(),
        )

    override suspend fun fetchExtended(
        selectedInterface: A2aInterfaceSnapshot,
        bearer: String,
    ): String {
        require(bearer.isNotBlank() && bearer.toByteArray().size <= MAX_BEARER_BYTES) {
            "A2A bearer credential is empty or oversized"
        }
        val base = selectedInterface.endpoint.full.toHttpUrl()
        val builder =
            Request
                .Builder()
                .header("Authorization", "Bearer $bearer")
                .header("A2A-Version", selectedInterface.protocolVersion)
        val request =
            when (selectedInterface.binding) {
                A2aBinding.JSON_RPC -> {
                    val payload =
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", EXTENDED_CARD_REQUEST_ID)
                            put("method", "GetExtendedAgentCard")
                            selectedInterface.tenant?.let { tenant ->
                                put("params", buildJsonObject { put("tenant", tenant) })
                            }
                        }.toString()
                    builder
                        .url(base)
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                }

                A2aBinding.HTTP_JSON -> {
                    val url =
                        base
                            .newBuilder()
                            .addPathSegment("extendedAgentCard")
                            .apply { selectedInterface.tenant?.let { addQueryParameter("tenant", it) } }
                            .build()
                    builder.url(url).get().build()
                }
            }
        val body = execute(request)
        if (selectedInterface.binding == A2aBinding.HTTP_JSON) return body
        val envelope = Json.parseToJsonElement(body) as? JsonObject ?: error("A2A JSON-RPC response must be an object")
        require(envelope["error"] == null) { "A2A extended Agent Card request failed" }
        return envelope["result"]?.jsonObject?.toString() ?: error("A2A JSON-RPC response has no object result")
    }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "A2A Agent Card request failed with HTTP ${response.code}" }
                require(response.isJson()) { "A2A Agent Card response is not JSON" }
                readBounded(response)
            }
        }

    private fun Response.isJson(): Boolean {
        val type = body.contentType() ?: return false
        return type.type == "application" && (type.subtype == "json" || type.subtype == "a2a+json")
    }

    private fun readBounded(response: Response): String {
        val body = response.body
        body.contentLength().takeIf { it >= 0 }?.let {
            require(it <= MAX_CARD_BYTES) { "A2A Agent Card response exceeds $MAX_CARD_BYTES bytes" }
        }
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_CARD_BYTES) throw IOException("A2A Agent Card response exceeds limit")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_CARD_BYTES = 512 * 1024
        const val MAX_BEARER_BYTES = 4_096
        const val BUFFER_SIZE = 8_192
        const val EXTENDED_CARD_REQUEST_ID = "helix-agent-card"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class A2aDiscoveryService internal constructor(
    private val client: A2aAgentCardClient,
    private val credentials: A2aCredentialLookup,
) {
    suspend fun discover(config: A2aAgentConfig): A2aAgentCardSnapshot {
        val publicRaw = client.fetchPublic(config.cardEndpoint)
        val publicCard =
            A2aAgentCardParser.parse(
                agentId = config.id,
                expectedOrigin = config.cardEndpoint.origin,
                raw = publicRaw,
                extended = false,
            )
        val alias = config.bearerSecretAlias
        if (!publicCard.capabilities.extendedAgentCard || alias == null) return publicCard
        val extendedRaw = client.fetchExtended(publicCard.selectedInterface, credentials.lookup(alias))
        return A2aAgentCardParser.parse(
            agentId = config.id,
            expectedOrigin = config.cardEndpoint.origin,
            raw = extendedRaw,
            extended = true,
        )
    }
}

object A2aClients {
    fun discovery(credentials: A2aCredentialLookup): A2aDiscoveryService =
        A2aDiscoveryService(
            OkHttpA2aAgentCardClient(
                OkHttpClient
                    .Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build(),
            ),
            credentials,
        )

    fun task(): A2aTaskClient =
        OkHttpA2aTaskClient(
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
}
