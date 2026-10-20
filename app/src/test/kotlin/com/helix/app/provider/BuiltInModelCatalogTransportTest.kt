package com.helix.app.provider

import com.helix.app.account.BuiltInAccountWire
import com.helix.app.account.testAccount
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInModelCatalogTransportTest {
    @Test fun dynamicDirectoryUsesOnlyPinnedGetWithAccountAndNoGeneration() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val http = OkHttpWireClient(followRedirects = false, retryConnections = false)
                val loopback =
                    object : WireClient {
                        override suspend fun open(request: WireRequest): WireResponse {
                            assertEquals(BuiltInModel.CATALOG_URL, request.url)
                            assertEquals("GET", request.method)
                            assertNull(request.body)
                            return http.open(
                                WireRequest(request.method, server.url("/v1/models").toString(), request.headers, null),
                            )
                        }
                    }
                val provider =
                    ProviderFactory(
                        { error("Directory must not resolve secrets") },
                        loopback,
                        { error("Directory must not resolve images") },
                        builtInWire =
                            BuiltInAccountWire(testAccount(), loopback),
                    ).create(builtInConfig())
                server.enqueue(
                    MockResponse.Builder().body("""{"data":[{"id":"future-model"},{"id":"Qwen3.8-27B"}]}""").build(),
                )
                assertEquals(
                    ModelCatalogResult.Listed(listOf("future-model", BuiltInModel.MODEL_ID)),
                    provider.listModels(),
                )
                val request = server.takeRequest()
                assertEquals("/v1/models", request.url.encodedPath)
                assertEquals("Bearer fixture-access", request.headers["Authorization"])
                assertEquals(BuiltInModel.CLIENT_ID, request.headers["X-Client-Id"])
                assertNull(request.headers["Cookie"])
                server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
                assertEquals(ModelCatalogResult.Listed(emptyList()), provider.listModels())
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(401)
                        .body("private gateway diagnostic")
                        .build(),
                )
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(401)
                        .body("private gateway diagnostic")
                        .build(),
                )
                val failure = provider.listModels() as ModelCatalogResult.Failed
                assertEquals(ModelErrorCode.AUTH, failure.code)
                assertTrue(!failure.detail.contains("private gateway diagnostic"))
                assertEquals(4, server.requestCount)
            }
        }
}
