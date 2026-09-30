package com.helix.runtime.cli.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class AntigravityHttpException(
    val status: Int,
) : IOException("Antigravity HTTP $status")

/** One cancellable network owner. Redirects/retries never replay generation or forward credentials. */
internal class AntigravityHttp(
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
    private val origin: String = ORIGIN,
    private val tokenUrl: String = TOKEN_URL,
) : Closeable {
    private val stop = SubscriptionCancellation()
    private val client =
        client
            .newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    fun token(fields: Map<String, String>): JsonObject {
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        return send(tokenUrl, form, null, TOKEN_LIMIT)
    }

    fun call(
        method: String,
        payload: JsonObject,
        token: String,
    ): JsonObject {
        require(method in setOf("loadCodeAssist", "fetchAvailableModels"))
        return send(
            "$origin/v1internal:$method",
            payload.toString().toRequestBody(CodexSubscriptionModel.JSON),
            token,
            RESPONSE_LIMIT,
        )
    }

    /** Generation has no total response duration/byte limit; frames and spool reads remain bounded. */
    fun generate(
        payload: JsonObject,
        token: String,
        decoder: com.helix.provider.api.StreamDecoder,
        eventDirectory: java.io.File?,
        onEvents: (List<com.helix.core.model.ModelEvent>) -> Unit,
    ): List<com.helix.core.model.ModelEvent> {
        stop.checkActive()
        val request =
            Request
                .Builder()
                .url("$origin/v1internal:streamGenerateContent?alt=sse")
                .post(payload.toString().toRequestBody(CodexSubscriptionModel.JSON))
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .header("User-Agent", "antigravity/1.104.0 helix")
                .build()
        val streaming =
            client
                .newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        val call = streaming.newCall(request)
        return stop.using(Closeable { call.cancel() }) {
            call.execute().use { response ->
                readSubscriptionEvents(response, decoder, eventDirectory = eventDirectory, onEvents = onEvents)
            }
        }
    }

    private fun send(
        url: String,
        body: RequestBody,
        token: String?,
        limit: Long,
    ): JsonObject {
        stop.checkActive()
        val request =
            Request
                .Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/json")
                .header("User-Agent", "antigravity/1.104.0 helix")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
        val call = client.newCall(request)
        return stop.using(Closeable { call.cancel() }) {
            call.execute().use { response ->
                if (!response.isSuccessful) throw AntigravityHttpException(response.code)
                val source = response.body.source()
                require(!source.request(limit + 1)) { "Antigravity response too large" }
                stop.checkActive()
                val bytes = source.readByteArray()
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            }
        }
    }

    override fun close() = stop.cancel()

    companion object {
        const val ORIGIN = "https://daily-cloudcode-pa.googleapis.com"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val TOKEN_LIMIT = 64 * 1024L
        const val RESPONSE_LIMIT = 8 * 1024 * 1024L
    }
}
