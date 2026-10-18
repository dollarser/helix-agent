package com.helix.provider.api.wire

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicClientWireClientTest {
    private val target = "https://fixture.invalid/v1/chat/completions"
    private val received = mutableListOf<WireRequest>()
    private val delegate =
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse {
                received += request
                return WireResponse(
                    204,
                    emptyMap(),
                    object : WireBody {
                        override suspend fun bytes() = byteArrayOf()

                        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) = Unit

                        override fun close() = Unit
                    },
                )
            }
        }
    private val client = PublicClientWireClient(target, "public-test-v1", delegate)

    @Test fun publicIdentityIsAddedOnlyToExactGenerationTarget() =
        runBlocking {
            for (url in listOf(target, target.replace("fixture.invalid/", "fixture.invalid:443/"))) {
                client.open(WireRequest("POST", url, mapOf("Content-Type" to "application/json"), "{}".toByteArray()))
            }
            assertEquals(2, received.size)
            received.forEach {
                assertEquals(target, it.url)
                assertEquals(mapOf("Content-Type" to "application/json", "X-Client-Id" to "public-test-v1"), it.headers)
            }
        }

    @Test fun changedOriginPortPathMethodAndCredentialsAreRejectedBeforeTransport() {
        val urls =
            listOf(
                target.replace("https:", "http:"),
                target.replace("fixture.invalid", "other.invalid"),
                target.replace("fixture.invalid/", "fixture.invalid:444/"),
                "$target/",
                "$target?key=x",
                target.replace("chat/completions", "models"),
                "https://fixture.invalid:30007/v1/chat/completions",
            )
        for (url in urls) rejects(WireRequest("POST", url, emptyMap(), null))
        rejects(WireRequest("GET", target, emptyMap(), null))
        for (key in listOf("Authorization", "AUTHORIZATION", "Cookie", "Proxy-Authorization", "x-client-id")) {
            rejects(WireRequest("POST", target, mapOf(key to "unexpected"), null))
        }
        assertEquals(0, received.size)
    }

    @Test fun insecureEndpointAndInvalidIdAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PublicClientWireClient(target.replace("https:", "http:"), "id", delegate)
        }
        for (id in listOf("", "a\nb", "a b", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { PublicClientWireClient(target, id, delegate) }
        }
    }

    private fun rejects(request: WireRequest) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.open(request) } }
    }
}
