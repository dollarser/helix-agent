package com.helix.provider.api

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ContextWindowDiscoveryTest {
    @Test fun invalidAndStringWindowsAreUnknown() {
        for (value in listOf("0", "-1", "1000001", "\"200000\"", "null", "{}")) {
            assertNull(ContextWindowDiscovery.window(Json.parseToJsonElement("{\"context_length\":$value}").jsonObject))
        }
        assertEquals(
            262144L,
            ContextWindowDiscovery.window(Json.parseToJsonElement("""{"context_length":262144}""").jsonObject),
        )
    }

    @Test fun exactCatalogWinsAndEveryBodyCloses() =
        runBlocking {
            val wire =
                FixtureWire(listOf("""{"data":[{"id":"a","context_length":65536},{"id":"b","context_length":8192}]}"""))
            assertEquals(
                8192L,
                ContextWindowDiscovery.read(
                    wire,
                    emptyMap(),
                    "https://example.test/models",
                    "https://example.test/get_server_info",
                    "b",
                ),
            )
            assertEquals(1, wire.calls)
            assertEquals(1, wire.closed)
        }

    @Test fun serverGlobalWindowRequiresOneMatchingModel() =
        runBlocking {
            val wire = FixtureWire(listOf("""{"data":[{"id":"a"}]}""", """{"context_length":262144}"""))
            assertEquals(
                262144L,
                ContextWindowDiscovery.read(
                    wire,
                    emptyMap(),
                    "https://example.test/models",
                    "https://example.test/get_server_info",
                    "a",
                ),
            )
            assertEquals(2, wire.closed)
            val multiple = FixtureWire(listOf("""{"data":[{"id":"a"},{"id":"b"}]}"""))
            assertNull(
                ContextWindowDiscovery.read(
                    multiple,
                    emptyMap(),
                    "https://example.test/models",
                    "https://example.test/info",
                    "a",
                ),
            )
            assertEquals(1, multiple.calls)
        }

    @Test fun cancellationIsNotConvertedToUnknown() {
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse = throw CancellationException("cancelled")
            }
        assertThrows(CancellationException::class.java) {
            runBlocking { ContextWindowDiscovery.read(wire, emptyMap(), "https://example.test/models", null, "a") }
        }
    }

    private class FixtureWire(
        private val replies: List<String>,
    ) : WireClient {
        var calls = 0
        var closed = 0

        override suspend fun open(request: WireRequest): WireResponse {
            val text = replies[calls++]
            return WireResponse(
                200,
                emptyMap(),
                object : WireBody {
                    override suspend fun bytes(): ByteArray = text.toByteArray()

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                        onChunk(bytes())
                    }

                    override fun close() {
                        closed++
                    }
                },
            )
        }
    }
}
