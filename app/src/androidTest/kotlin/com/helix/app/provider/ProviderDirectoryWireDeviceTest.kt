package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Production factory and protocol adapters with a recorded wire boundary, never a real socket. */
class ProviderDirectoryWireDeviceTest {
    @Test fun openAiDirectoryUsesOnlyGetForPopulatedAndEmptyLists() =
        runBlocking {
            val protocols = listOf(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, ProviderProtocol.OPENAI_RESPONSES)
            for (protocol in protocols) {
                for (models in listOf(listOf("different-model"), emptyList())) {
                    Fixture(protocol, models).use { f ->
                        val outcome = f.service.runConnectionTest("p") as ProbeOutcome.Ok
                        assertEquals(models, outcome.models)
                        assertEquals(listOf("GET"), f.requests.map { it.method })
                        assertEquals(listOf("http://127.0.0.1:17771/v1/models"), f.requests.map { it.url })
                        assertTrue(
                            f.service.rows.value
                                .single()
                                .modelGenerations
                                .isEmpty(),
                        )
                        assertTrue(
                            f.service.rows.value
                                .single()
                                .modelVerifications
                                .isEmpty(),
                        )
                    }
                }
            }
        }

    @Test fun anthropicUnsupportedDirectoryNeverMakesANetworkRequest() =
        runBlocking {
            Fixture(ProviderProtocol.ANTHROPIC_MESSAGES, emptyList()).use { f ->
                val outcome = f.service.runConnectionTest("p") as ProbeOutcome.Failed
                assertEquals("MODEL_CATALOG_UNSUPPORTED", outcome.detail)
                assertEquals(emptyList<WireRequest>(), f.requests)
                assertTrue((f.status.statusFor("p") as ConnectionTestStatus.Failed).catalogUnsupported)
            }
        }

    private class DirectoryWire(
        private val models: List<String>,
        private val requests: MutableList<WireRequest>,
    ) : WireClient {
        override suspend fun open(request: WireRequest): WireResponse {
            requests += request
            check(request.method == "GET" && request.url == "http://127.0.0.1:17771/v1/models") {
                "Directory check made an unexpected request: ${request.method} ${request.url}"
            }
            val data = models.joinToString(",") { "{\"id\":\"$it\"}" }
            val body =
                object : WireBody {
                    override suspend fun bytes() = "{\"data\":[$data]}".toByteArray()

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                        error("A directory check must not consume a generation stream")
                    }

                    override fun close() = Unit
                }
            return WireResponse(200, emptyMap(), body)
        }
    }

    private class Fixture(
        protocol: ProviderProtocol,
        models: List<String>,
    ) : AutoCloseable {
        private val context: Context = ApplicationProvider.getApplicationContext()
        private val suffix = UUID.randomUUID().toString()
        private val directory = File(context.cacheDir, "directory-wire-$suffix")
        private val database = "directory-wire-$suffix.db"
        private val storage = HelixStorage.open(context, database, directory)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = CopyOnWriteArrayList<WireRequest>()
        val status = ProviderTestStatusStore(InMemoryLineStore())
        val service: ProviderService

        init {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "p",
                    displayName = "Directory fixture",
                    protocol = protocol,
                    endpoint = "http://127.0.0.1:17771/v1",
                    model = "unavailable-base-model",
                    headersJson = "{}",
                    secretAlias = null,
                    capabilitySnapshot = UNTESTED_PROVIDER_SNAPSHOT,
                    authKind = "NONE",
                ),
            )
            val wire = DirectoryWire(models, requests)
            service =
                ProviderService(
                    storage = storage,
                    factory =
                        ProviderFactory(
                            CredentialLookup { error("No credentials") },
                            wire,
                            { error("No images") },
                        ),
                    testStatus = status,
                    idGenerator = { UUID.randomUUID().toString() },
                    scope = scope,
                )
        }

        override fun close() {
            scope.cancel()
            storage.close()
            context.deleteDatabase(database)
            directory.deleteRecursively()
        }
    }
}
