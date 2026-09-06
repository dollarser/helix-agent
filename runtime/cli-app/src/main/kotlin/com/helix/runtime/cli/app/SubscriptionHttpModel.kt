package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** OAuth and network stay in this UID; ordinary Provider wire codecs are shared. */
internal class SubscriptionHttpModel(
    private val vault: CliSubscriptionCredentialVault,
    private val refresh: () -> Unit,
    private val platform: CliSubscriptionProvider,
    private val url: String,
    private val encode: (ModelRequest) -> String,
    private val decoder: () -> com.helix.provider.api.StreamDecoder,
    private val headers: Map<String, String> = emptyMap(),
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun run(request: ModelRequest): CodexModelExecution {
        if (request.tools.isNotEmpty() || request.messages.any { it.images.isNotEmpty() || it.toolCalls.isNotEmpty() || it.toolCallId != null }) {
            return CodexModelExecution(request.model, listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)))
        }
        if (!vault.contains(platform)) {
            return CodexModelExecution(request.model, listOf(ModelEvent.Error(ModelErrorCode.AUTH, false)))
        }
        // Refresh before sending a model request; an ambiguous POST is never replayed.
        if (vault.load(platform).expiresAtEpochMillis <= System.currentTimeMillis() + 30_000) refresh()
        val session = vault.load(platform)
        val httpRequest = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Accept", "text/event-stream")
            .post(encode(request).toRequestBody(CodexSubscriptionModel.JSON)).build()
        return client.newCall(httpRequest).execute().use { response ->
            CodexModelExecution(request.model, readSubscriptionEvents(response, decoder()))
        }
    }

    override fun close() { client.dispatcher.cancelAll() }

}
