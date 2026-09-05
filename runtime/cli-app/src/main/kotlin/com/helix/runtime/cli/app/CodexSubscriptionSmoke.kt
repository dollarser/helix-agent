package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class CodexSmokeResult(
    val model: String,
    val text: String,
)

internal class CodexSmokeException(
    val stage: String,
    val httpCode: Int? = null,
) : IllegalStateException("Codex subscription smoke failed at $stage")

/**
 * Runtime-UID-only, user-triggered subscription feasibility probe.
 *
 * It deliberately accepts no caller prompt or tools, never exposes credentials, and bounds both
 * request and response. This is not the production Provider/job protocol.
 */
internal class CodexSubscriptionSmoke(
    private val vault: CliSubscriptionCredentialVault,
    private val oauth: CodexLoginController,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val client =
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    fun run(): CodexSmokeResult {
        var session = vault.load(CliSubscriptionProvider.CODEX)
        var modelResult = discoverModel(session.accessToken, session.accountId ?: throw CodexSmokeException("credential"))
        if (modelResult.httpCode == 401) {
            try {
                oauth.refresh()
            } catch (error: Exception) {
                when (error) {
                    is CodexOAuthEndpointException, is IOException -> throw error
                    else -> throw CodexSmokeException("refresh-${safeProtocolReason(error.message)}")
                }
            }
            session = vault.load(CliSubscriptionProvider.CODEX)
            modelResult = discoverModel(session.accessToken, session.accountId ?: throw CodexSmokeException("credential"))
        }
        val model = modelResult.model ?: throw CodexSmokeException("models", modelResult.httpCode)
        val accountId = session.accountId ?: throw CodexSmokeException("credential")
        val requestBody = encodeRequest(model)
        val request =
            Request.Builder()
                .url(RESPONSES_URL)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("chatgpt-account-id", accountId)
                .header("originator", "codex_cli_rs")
                .header("session-id", UUID.randomUUID().toString())
                .header("Accept", "text/event-stream")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorCode = runCatching {
                    val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
                    val error = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["error"]?.jsonObject
                    error?.get("code")?.jsonPrimitive?.contentOrNull
                        ?: error?.get("type")?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,64}")) }
                throw CodexSmokeException("response-${errorCode ?: "rejected"}", response.code)
            }
            val source = response.body.source()
            var total = 0L
            val text = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                total += line.encodeToByteArray().size + 1
                if (total > MAX_STREAM_BYTES) throw CodexSmokeException("response-too-large")
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]" || data.isEmpty()) continue
                val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: throw CodexSmokeException("response-protocol")
                when (event["type"]?.jsonPrimitive?.contentOrNull) {
                    "response.output_text.delta" -> {
                        val delta = event["delta"]?.jsonPrimitive?.contentOrNull
                            ?: throw CodexSmokeException("response-protocol")
                        text.append(delta)
                        if (text.length > MAX_TEXT_CHARS) throw CodexSmokeException("output-too-large")
                    }
                    "response.failed", "response.incomplete", "error" -> {
                        throw CodexSmokeException("response-terminal")
                    }
                }
            }
            val normalized = text.toString().trim()
            if (normalized != EXPECTED_TEXT) throw CodexSmokeException("unexpected-output")
            return CodexSmokeResult(model, normalized)
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
    }

    private fun discoverModel(accessToken: String, accountId: String): ModelDiscovery {
        val request =
            Request.Builder()
                .url("$MODELS_URL?client_version=$CLIENT_VERSION")
                .header("Authorization", "Bearer $accessToken")
                .header("chatgpt-account-id", accountId)
                .header("originator", "codex_cli_rs")
                .header("Accept", "application/json")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ModelDiscovery(null, response.code)
            val bytes = try {
                response.body.source().readBoundedByteArray(MAX_CATALOG_BYTES)
            } catch (_: IllegalArgumentException) {
                throw CodexSmokeException("models-too-large")
            }
            val models = runCatching {
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("models").jsonArray
            }.getOrElse { throw CodexSmokeException("models-protocol") }
            val model = models.firstNotNullOfOrNull { item ->
                val row = item.jsonObject
                val visibility = row["visibility"]?.jsonPrimitive?.contentOrNull
                row["slug"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= 128 && visibility !in setOf("hide", "none") }
            } ?: throw CodexSmokeException("models-empty")
            return ModelDiscovery(model, response.code)
        }
    }

    private data class ModelDiscovery(val model: String?, val httpCode: Int)

    private fun safeProtocolReason(message: String?): String =
        when {
            message?.contains("access_token") == true -> "missing-access"
            message?.contains("refresh token") == true -> "missing-refresh"
            message?.contains("expiry") == true -> "missing-expiry"
            message?.contains("account id") == true -> "missing-account"
            message?.contains("JWT") == true -> "invalid-jwt"
            message?.contains("credential") == true -> "credential"
            else -> "protocol"
        }

    internal companion object {
        const val MODELS_URL = "https://chatgpt.com/backend-api/codex/models"
        const val RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
        const val CLIENT_VERSION = "0.153.4"
        const val EXPECTED_TEXT = "HELIX_OK"
        const val MAX_TEXT_CHARS = 64
        const val MAX_CATALOG_BYTES = 1024L * 1024L
        const val MAX_STREAM_BYTES = 256L * 1024L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun encodeRequest(model: String): String =
            buildJsonObject {
                put("model", model)
                put("instructions", "Return only the exact requested text. Do not use tools.")
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "input_text"); put("text", "Reply exactly HELIX_OK") })
                                    },
                                )
                            },
                        )
                    },
                )
                put("store", false)
                put("stream", true)
                put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
            }.toString()
    }
}
