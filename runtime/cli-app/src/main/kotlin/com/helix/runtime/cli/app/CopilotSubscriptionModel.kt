package com.helix.runtime.cli.app

import com.helix.core.model.ModelRequest
import com.helix.provider.openai.chat.ChatCompletionsRequestEncoder
import com.helix.provider.openai.chat.ChatCompletionsStreamDecoder
import okhttp3.OkHttpClient
import java.io.Closeable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Experimental fixed Copilot endpoint; no credential leaves this Runtime UID. */
internal class CopilotSubscriptionModel(
    vault: CliSubscriptionCredentialVault,
    refresh: () -> Unit,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val http = SubscriptionHttpModel(vault, refresh, CliSubscriptionProvider.COPILOT, URL,
        ::encodeRequest, ::ChatCompletionsStreamDecoder, headers = mapOf(
            "User-Agent" to "GitHubCopilotChat/0.35.0",
            "Editor-Version" to "vscode/1.107.0",
            "Editor-Plugin-Version" to "copilot-chat/0.35.0",
            "Copilot-Integration-Id" to "vscode-chat",
        ), client = client)
    fun run(request: ModelRequest) = http.run(request)
    override fun close() = http.close()
    companion object {
        const val URL = "https://api.githubcopilot.com/chat/completions"
        fun encodeRequest(request: ModelRequest): String {
            val base = Json.parseToJsonElement(ChatCompletionsRequestEncoder { error("subscription images unsupported") }.encode(request)).jsonObject
            return buildJsonObject {
                base.forEach { (key, value) -> put(if (key == "max_tokens") "max_completion_tokens" else key, value) }
            }.toString()
        }
    }
}
