package com.helix.review

import com.helix.core.model.*
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.*
import com.helix.provider.openai.chat.OpenAiChatProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class ProviderReviewProbe {
    @Test
    fun doneSentinelStillWaitsForTransportEof() = runBlocking {
        var closed = false
        var drainedPastDone = false
        val body = object : WireBody {
            override suspend fun bytes(): ByteArray = error("unused")
            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                if (!onChunk(sse.toByteArray())) return
                drainedPastDone = true
                awaitCancellation()
            }
            override fun close() { closed = true }
        }
        val wire = object : WireClient {
            override suspend fun open(request: WireRequest) = WireResponse(200, emptyMap(), body)
        }
        val config = ProviderConfig("p", "review", ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            NormalizedEndpoint.parse("https://example.test/v1"), "m", emptyMap(), SecretAlias("fixture"), "{}")
        val provider = OpenAiChatProvider(config, { "fixture" }, wire) { error("unused") }
        val events = mutableListOf<ModelEvent>()
        val finished = withTimeoutOrNull(500) {
            provider.stream(ModelRequest("m", listOf(ModelMessage(ModelRole.USER, "hi")))).collect { events += it }
            true
        }
        assertNull(finished)
        assertTrue(events.any { it is ModelEvent.Completed })
        assertTrue(drainedPastDone)
        assertTrue(closed)
        println("REPRODUCED provider: Completed received, DONE consumed, flow still waits for EOF")
    }
}
