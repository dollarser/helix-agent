package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.helix.provider.catalog.ProviderTemplateCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDraftDiscoveryTest {
    @Test fun optionalCredentialsApplyToEverySelfHostedTemplate() =
        runBlocking {
            for (template in listOf(
                ProviderTemplateCatalog.sglang,
                ProviderTemplateCatalog.ollama,
                ProviderTemplateCatalog.vllm,
            )) {
                for (key in listOf(null, "fixture-token")) {
                    val wire = CatalogWire()
                    val factory =
                        ProviderFactory(
                            CredentialLookup { error("No persistent credential lookup") },
                            wire,
                            { error("No image") },
                        )
                    val draft =
                        (
                            ProviderComposer.compose(
                                template,
                                "Example",
                                "https://example.test/v1",
                                "catalog",
                                emptyMap(),
                            ) as ComposeOutcome.Ok
                        ).draft
                    val result = discoverDraftModels(factory, draft, key) as ModelCatalogResult.Listed
                    assertEquals(listOf("model-a", "model-b"), result.models)
                    assertEquals("GET", wire.request?.method)
                    assertEquals("https://example.test/v1/models", wire.request?.url)
                    val auth =
                        wire.request
                            ?.headers
                            ?.entries
                            ?.firstOrNull { it.key.equals("Authorization", true) }
                            ?.value
                    assertEquals(key?.let { "Bearer $it" }, auth)
                    assertTrue(wire.closed)
                }
            }
        }

    @Test fun cleartextDiscoveryUsesTheSelectedEndpointWithoutAnExtraConsentGate() =
        runBlocking {
            val wire = CatalogWire()
            val factory = ProviderFactory(CredentialLookup { error("No lookup") }, wire, { error("No image") })
            val draft =
                (
                    ProviderComposer.compose(
                        ProviderTemplateCatalog.sglang,
                        "Example",
                        "http://127.0.0.1:30000/v1",
                        "catalog",
                        emptyMap(),
                    ) as ComposeOutcome.Ok
                ).draft
            val result = discoverDraftModels(factory, draft, null) as ModelCatalogResult.Listed
            assertEquals(listOf("model-a", "model-b"), result.models)
            assertEquals("http://127.0.0.1:30000/v1/models", wire.request?.url)
            assertEquals("GET", wire.request?.method)
            assertTrue(wire.closed)
            assertTrue(draft.isCleartext)
        }

    @Test fun choicesSurviveReopenAndStaySeparateFromTestResults() {
        val backing = InMemoryLineStore()
        val store = ProviderTestStatusStore(backing)
        store.selectedModels.write("one", listOf("a", "b", "a"))
        store.clear("one")
        val reopened = ProviderTestStatusStore(backing)
        assertEquals(listOf("a", "b"), reopened.selectedModels.read("one"))
        assertEquals(ConnectionTestStatus.Untested, reopened.statusFor("one"))
        assertTrue(reopened.selectedModels.read("two").isEmpty())
        assertThrows(
            IllegalArgumentException::class.java,
        ) { reopened.selectedModels.write("one", listOf("bad\nmodel")) }
        assertEquals(listOf("a", "b"), reopened.selectedModels.read("one"))
    }

    private class CatalogWire : WireClient {
        var request: WireRequest? = null
        var closed = false

        override suspend fun open(request: WireRequest): WireResponse {
            this.request = request
            return WireResponse(
                200,
                emptyMap(),
                object : WireBody {
                    override suspend fun bytes() = """{"data":[{"id":"model-a"},{"id":"model-b"}]}""".toByteArray()

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                        error("No generation")
                    }

                    override fun close() {
                        closed = true
                    }
                },
            )
        }
    }
}
