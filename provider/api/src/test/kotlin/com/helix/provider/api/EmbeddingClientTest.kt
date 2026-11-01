package com.helix.provider.api

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingClientTest {
    @Test fun requestContractFailureAndCancellationAlwaysCloseTheBody() =
        runBlocking {
            val body = TestBody("""{"data":[{"index":0,"embedding":[3,4]}]}""")
            var observed: WireRequest? = null
            val client =
                EmbeddingClient(
                    object : WireClient {
                        override suspend fun open(request: WireRequest): WireResponse {
                            observed = request
                            return WireResponse(200, emptyMap(), body)
                        }
                    },
                    "https://example.test/v1/embeddings",
                )
            client.embed("synthetic", listOf("one synthetic passage"))
            assertTrue(body.closed)
            assertEquals("POST", observed!!.method)
            assertEquals("https://example.test/v1/embeddings", observed!!.url)
            assertEquals("application/json", observed!!.headers["Content-Type"])
            val content =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(observed!!.body!!.decodeToString())
            assertTrue(content.toString().contains("\"encoding_format\":\"float\""))
            val pending = TestBody("").apply { wait = true }
            val stopped =
                EmbeddingClient(
                    object : WireClient {
                        override suspend fun open(request: WireRequest) = WireResponse(200, emptyMap(), pending)
                    },
                    "https://example.test/v1/embeddings",
                )
            try {
                kotlinx.coroutines.withTimeout(30) { stopped.embed("synthetic", listOf("synthetic")) }
                error("Cancellation must propagate")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                assertTrue(pending.closed)
            }
            val rejected = TestBody("no server error body is exposed")
            try {
                EmbeddingClient(
                    object : WireClient {
                        override suspend fun open(request: WireRequest) = WireResponse(401, emptyMap(), rejected)
                    },
                    "https://example.test/v1/embeddings",
                ).embed("synthetic", listOf("synthetic"))
                error("HTTP rejection must fail")
            } catch (failure: java.io.IOException) {
                assertEquals("Embedding HTTP 401", failure.message)
                assertTrue(rejected.closed)
            }
        }

    @Test fun restoresBatchOrderAndNormalizesVectors() {
        val result =
            EmbeddingClient.parse(
                """{"data":[{"index":1,"embedding":[0,5]},{"index":0,"embedding":[3,4]}]}""",
                2,
            )
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), result[0], 0.00001f)
        assertArrayEquals(floatArrayOf(0f, 1f), result[1], 0.00001f)
    }

    @Test fun rejectsIncompleteDuplicateZeroOrInconsistentVectors() {
        listOf(
            """{"data":[]}""",
            """{"data":[{"index":0,"embedding":[0,0]}]}""",
            """{"data":[{"index":0,"embedding":["1",2]}]}""",
            """{"data":[{"index":1,"embedding":[1,2]}]}""",
        ).forEach { body -> assertThrows(IllegalArgumentException::class.java) { EmbeddingClient.parse(body, 1) } }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingClient.parse("""{"data":[{"index":0,"embedding":[1]},{"index":0,"embedding":[2]}]}""", 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingClient.parse("""{"data":[{"index":0,"embedding":[1]},{"index":1,"embedding":[1,2]}]}""", 2)
        }
    }
}

private class TestBody(
    private val text: String,
) : WireBody {
    var closed = false
    var wait = false

    override suspend fun bytes() = text.toByteArray()

    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
        if (wait) kotlinx.coroutines.awaitCancellation()
        onChunk(text.toByteArray())
    }

    override fun close() {
        closed = true
    }
}
