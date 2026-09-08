package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
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
        client
            .newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    fun run(): CodexSmokeResult {
        var session = vault.load(CliSubscriptionProvider.CODEX)
        var modelResult =
            discoverModel(
                session.accessToken,
                accountId(session),
            )
        if (modelResult.httpCode == 401) {
            oauth.refresh()
            session = vault.load(CliSubscriptionProvider.CODEX)
            modelResult =
                discoverModel(session.accessToken, accountId(session))
        }
        val model = modelResult.model ?: throw CodexSmokeException("models", modelResult.httpCode)
        val accountId = accountId(session)
        val requestBody = encodeRequest(model)
        val request =
            Request
                .Builder()
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
                val errorCode =
                    runCatching {
                        val bytes = response.body.source().readBoundedByteArray(64 * 1024L)
                        val error = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["error"]?.jsonObject
                        error?.get("code")?.jsonPrimitive?.contentOrNull
                            ?: error?.get("type")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,64}")) }
                throw CodexSmokeException("response-${errorCode ?: "rejected"}", response.code)
            }
            return CodexSmokeResult(model, CodexSmokeStream.read(response.body.source()))
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
    }

    private fun accountId(session: CliSubscriptionSession): String =
        session.accountId ?: throw CodexSmokeException("credential")

    private fun discoverModel(
        accessToken: String,
        accountId: String,
    ): ModelDiscovery {
        val request =
            Request
                .Builder()
                .url("$MODELS_URL?client_version=$CLIENT_VERSION")
                .header("Authorization", "Bearer $accessToken")
                .header("chatgpt-account-id", accountId)
                .header("originator", "codex_cli_rs")
                .header("Accept", "application/json")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ModelDiscovery(null, response.code)
            val model = CodexSmokeCatalog.read(response.body.source())
            return ModelDiscovery(model, response.code)
        }
    }

    private data class ModelDiscovery(
        val model: String?,
        val httpCode: Int,
    )

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
                                        add(
                                            buildJsonObject {
                                                put("type", "input_text")
                                                put("text", "Reply exactly HELIX_OK")
                                            },
                                        )
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
