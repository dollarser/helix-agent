package com.helix.runtime.cli.app

import com.helix.core.model.ModelRequest
import com.helix.provider.openai.responses.ResponsesRequestEncoder
import com.helix.provider.openai.responses.ResponsesStreamDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.Closeable

internal class GrokSubscriptionModel(
    vault: CliSubscriptionCredentialVault,
    refresh: () -> Unit,
    client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),
) : Closeable {
    private val http = SubscriptionHttpModel(vault, refresh, CliSubscriptionProvider.GROK, URL,
        ::encodeRequest, ::ResponsesStreamDecoder, client = client)
    fun run(request: ModelRequest) = http.run(request)
    override fun close() = http.close()
    companion object {
        const val URL = "https://api.x.ai/v1/responses"
        fun encodeRequest(request: ModelRequest): String {
            val base = Json.parseToJsonElement(ResponsesRequestEncoder { error("subscription images unsupported") }.encode(request)).jsonObject
            return buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("store", false)
            }.toString()
        }
    }
}
