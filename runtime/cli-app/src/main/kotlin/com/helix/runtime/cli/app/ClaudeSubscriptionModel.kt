package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.anthropic.AnthropicRequestEncoder
import com.helix.provider.anthropic.AnthropicStreamDecoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** OAuth and network stay in this UID; the ordinary Anthropic wire codec is shared. */
internal class ClaudeSubscriptionModel(
    private val vault: CliSubscriptionCredentialVault,
    private val refresh: () -> Unit,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val encoder = AnthropicRequestEncoder { error("subscription images unsupported") }

    fun run(request: ModelRequest): CodexModelExecution {
        if (request.tools.isNotEmpty() || request.messages.any { it.images.isNotEmpty() || it.toolCalls.isNotEmpty() || it.toolCallId != null }) {
            return CodexModelExecution(request.model, listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)))
        }
        if (!vault.contains(CliSubscriptionProvider.CLAUDE)) {
            return CodexModelExecution(request.model, listOf(ModelEvent.Error(ModelErrorCode.AUTH, false)))
        }
        // Refresh before sending a model request; an ambiguous POST is never replayed.
        if (vault.load(CliSubscriptionProvider.CLAUDE).expiresAtEpochMillis <= System.currentTimeMillis() + 30_000) refresh()
        val session = vault.load(CliSubscriptionProvider.CLAUDE)
        val httpRequest = Request.Builder().url(URL)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "oauth-2025-04-20")
            .header("Accept", "text/event-stream")
            .post(encoder.encode(request).toRequestBody(CodexSubscriptionModel.JSON)).build()
        return client.newCall(httpRequest).execute().use { response ->
            CodexModelExecution(request.model, readSubscriptionEvents(response, AnthropicStreamDecoder()))
        }
    }

    override fun close() { client.dispatcher.cancelAll() }

    companion object {
        const val URL = "https://api.anthropic.com/v1/messages?beta=true"
    }
}
