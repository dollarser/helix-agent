package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.openai.responses.ResponsesRequestEncoder
import com.helix.provider.openai.responses.ResponsesStreamDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class CodexModelExecution(val model: String, val events: List<ModelEvent>)

internal class CodexSubscriptionModel(
    private val vault: CliSubscriptionCredentialVault,
    private val oauth: CodexLoginController,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private val encoder = ResponsesRequestEncoder { error("image references are rejected by the IPC codec") }

    fun run(request: ModelRequest): CodexModelExecution {
        var session = vault.load(CliSubscriptionProvider.CODEX)
        var response = execute(request, session)
        if (response.code == 401) {
            response.close()
            oauth.refresh()
            session = vault.load(CliSubscriptionProvider.CODEX)
            response = execute(request, session)
        }
        response.use { http ->
            return CodexModelExecution(request.model, readSubscriptionEvents(http, ResponsesStreamDecoder()))
        }
    }

    override fun close() { client.dispatcher.cancelAll() }

    private fun execute(request: ModelRequest, session: CliSubscriptionSession): okhttp3.Response {
        val accountId = session.accountId ?: throw CodexSmokeException("credential")
        val base = Json.parseToJsonElement(encodeSubscriptionRequest(request, encoder)).jsonObject
        val body = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("store", false)
        }.toString()
        val call = Request.Builder().url(CodexSubscriptionSmoke.RESPONSES_URL)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("chatgpt-account-id", accountId).header("originator", "codex_cli_rs")
            .header("session-id", UUID.randomUUID().toString()).header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON)).build()
        return client.newCall(call).execute()
    }

    internal companion object {
        const val MAX_STREAM_BYTES = 2L * 1024L * 1024L
        const val MAX_EVENTS = 2048
        val JSON = "application/json".toMediaType()

        /** The consumer-subscription endpoint rejects the public API output-token field. */
        fun encodeSubscriptionRequest(
            request: ModelRequest,
            encoder: ResponsesRequestEncoder = ResponsesRequestEncoder {
                error("image references are rejected by the IPC codec")
            },
        ): String = encoder.encode(request.copy(maxOutputTokens = null))
    }
}
