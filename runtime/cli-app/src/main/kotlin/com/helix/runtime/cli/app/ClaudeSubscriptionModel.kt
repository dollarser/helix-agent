package com.helix.runtime.cli.app

import com.helix.core.model.ModelRequest
import com.helix.provider.anthropic.AnthropicRequestEncoder
import com.helix.provider.anthropic.AnthropicStreamDecoder
import okhttp3.OkHttpClient
import java.io.Closeable

internal class ClaudeSubscriptionModel(
    vault: CliSubscriptionCredentialVault,
    refresh: () -> Unit,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val http =
        SubscriptionHttpModel(
            vault,
            refresh,
            CliSubscriptionProvider.CLAUDE,
            URL,
            AnthropicRequestEncoder { error("subscription images unsupported") }::encode,
            ::AnthropicStreamDecoder,
            mapOf("anthropic-version" to "2023-06-01", "anthropic-beta" to "oauth-2025-04-20"),
            client,
        )

    fun run(request: ModelRequest) = http.run(request)

    override fun close() = http.close()

    companion object {
        const val URL = "https://api.anthropic.com/v1/messages?beta=true"
    }
}
