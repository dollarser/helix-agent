package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.agent.LocalModelCallContext
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugModelCorrelationTest {
    @Test fun gateUsesExplicitSourceInsteadOfDomainGuessing() {
        ProviderProtocol.entries.forEach { protocol ->
            val config = config(protocol)
            assertTrue(debugModelCorrelationEnabled(true, config, true))
            assertFalse(debugModelCorrelationEnabled(false, config, true))
            assertFalse(debugModelCorrelationEnabled(true, config, false))
        }
        val local =
            config().copy(
                connection =
                    ProviderConnection(
                        ProviderProvisioningKind.ON_DEVICE_ASSET,
                        ProviderTransport.OnDeviceLocal,
                        ProviderAuth.None,
                    ),
            )
        val managed =
            config().copy(
                connection =
                    ProviderConnection(
                        ProviderProvisioningKind.MANAGED_ACCOUNT,
                        config().transport,
                        ProviderAuth.ManagedAccount,
                    ),
            )
        assertFalse(debugModelCorrelationEnabled(true, local, true))
        assertFalse(debugModelCorrelationEnabled(true, managed, true))
    }

    @Test fun explicitCustomChoiceIsBoundToProviderAndTransportAndCanBeCleared() {
        val memory = InMemoryLineStore()
        val store = DebugCustomApiStore(memory, true)
        val config = config()
        val draft =
            ProviderDraft(
                "generic-openai",
                "fixture",
                config.protocol,
                config.endpoint,
                "fixture",
                "{}",
                false,
            )
        assertFalse(store.enabled(config))
        store.record(config.id, draft)
        assertTrue(DebugCustomApiStore(memory, true).enabled(config))
        assertFalse(DebugCustomApiStore(memory, false).enabled(config))
        assertFalse(store.enabled(config.copy(id = "another")))
        assertFalse(
            store.enabled(
                config.copy(
                    connection =
                        config.connection.copy(
                            transport =
                                ProviderTransport.Network(
                                    config.protocol,
                                    NormalizedEndpoint.parse("https://other.example/v1"),
                                ),
                        ),
                ),
            ),
        )
        store.record(config.id, draft.copy(templateId = "openai"))
        assertFalse(store.enabled(config))
        store.record(config.id, draft)
        store.clear(config.id)
        assertFalse(store.enabled(config))
        DebugCustomApiStore(memory, false).record(config.id, draft)
        assertFalse(store.enabled(config))
    }

    @Test fun allProtocolsCarryIdsOnlyForDebugCustomApiAndKeepJsonIdentical() =
        runBlocking {
            ProviderProtocol.entries.forEach { protocol ->
                val wire = CaptureWire()
                val request =
                    ModelRequest("fixture", listOf(ModelMessage(ModelRole.USER, "fixture")), maxOutputTokens = 128)
                for ((debug, custom) in listOf(false to true, true to false, true to true)) {
                    val factory =
                        ProviderFactory(
                            { error("No credential required") },
                            wire,
                            { error("No image") },
                            debuggable = debug,
                            customApiForDebug = { custom },
                        )
                    val events =
                        withContext(LocalModelCallContext("turn-1", "call-1")) {
                            factory.create(config(protocol)).stream(request).toList()
                        }
                    assertEquals(ModelErrorCode.AUTH, (events.single() as ModelEvent.Error).code)
                }
                val baseline = wire.requests.first()
                assertTrue(
                    wire.requests[1]
                        .headers.keys
                        .none { it.startsWith("x-helix-") },
                )
                assertTrue(baseline.headers.keys.none { it.startsWith("x-helix-") })
                assertEquals("turn-1", wire.requests.last().headers["x-helix-turn-id"])
                assertEquals("call-1", wire.requests.last().headers["x-helix-model-call-id"])
                assertTrue(baseline.body!!.contentEquals(wire.requests.last().body!!))
            }
        }

    @Test fun absentIdentityAndGetRequestsRemainUnchanged() =
        runBlocking {
            val wire = CaptureWire()
            val debug = DebugModelCorrelationWireClient(wire)
            val post = request()
            debug.open(post)
            assertSame(post, wire.requests.last())
            val get = WireRequest("GET", "https://fixture.example/v1/models", emptyMap(), null)
            withContext(LocalModelCallContext("turn", "call")) { debug.open(get) }
            assertSame(get, wire.requests.last())
        }

    @Test fun repeatedConcurrentCallsDoNotShareMutableIdentity() =
        runBlocking {
            val wire = CaptureWire()
            val debug = DebugModelCorrelationWireClient(wire)
            (1..3)
                .map { index ->
                    async(LocalModelCallContext("turn-$index", "call-$index")) { repeat(2) { debug.open(request()) } }
                }.awaitAll()
            (1..3).forEach { index ->
                assertEquals(
                    2,
                    wire.requests.count {
                        it.headers["x-helix-turn-id"] == "turn-$index" &&
                            it.headers["x-helix-model-call-id"] == "call-$index"
                    },
                )
            }
        }

    @Test fun invalidOrForgedIdsFailBeforeTransportAndCancellationPropagates() {
        val wire = CaptureWire()
        val debug = DebugModelCorrelationWireClient(wire)
        listOf("", "a\r\nb", "你好", "a".repeat(129)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking(LocalModelCallContext(value, "call")) {
                    debug.open(request())
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking(LocalModelCallContext("turn", "call")) {
                debug.open(WireRequest("POST", request().url, mapOf("X-Helix-Turn-Id" to "forged"), request().body))
            }
        }
        assertTrue(wire.requests.isEmpty())
        val cancellingWire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse = throw CancellationException("cancelled")
            }
        val cancelled = DebugModelCorrelationWireClient(cancellingWire)
        assertThrows(CancellationException::class.java) {
            runBlocking(LocalModelCallContext("turn", "call")) {
                cancelled.open(request())
            }
        }
    }

    private fun config(protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS) =
        ProviderConfig(
            "p",
            "fixture",
            ProviderConnection(
                ProviderProvisioningKind.USER_CONFIGURED,
                ProviderTransport.Network(protocol, NormalizedEndpoint.parse("https://fixture.example/v1")),
                ProviderAuth.None,
            ),
            "fixture",
            emptyMap(),
            "{}",
        )

    private fun request() =
        WireRequest("POST", "https://fixture.example/v1/chat/completions", emptyMap(), "{}".toByteArray())

    private class CaptureWire : WireClient {
        val requests = java.util.Collections.synchronizedList(ArrayList<WireRequest>())

        override suspend fun open(request: WireRequest): WireResponse {
            requests += request
            return WireResponse(
                401,
                emptyMap(),
                object : WireBody {
                    override suspend fun bytes(): ByteArray = ByteArray(0)

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) = Unit

                    override fun close() = Unit
                },
            )
        }
    }
}
