package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelPurposeTest {
    private fun parse(fields: String) = ModelPurposes.parse(Json.parseToJsonElement(fields).jsonObject)

    @Test fun standardDirectoryAndOpaqueAliasesRemainUnknown() {
        assertEquals(
            emptySet<ModelPurpose>(),
            parse("""{"id":"embedding-looking-name","object":"model","owned_by":"x"}"""),
        )
        assertEquals(
            emptySet<ModelPurpose>(),
            parse("""{"id":"chat-alias","type":"text","capabilities":{"vision":true,"tool_calls":true}}"""),
        )
    }

    @Test fun explicitTasksAndMultipleCapabilitiesBeatNames() {
        assertEquals(setOf(ModelPurpose.EMBEDDING), parse("""{"id":"chat-great","sub_type":"embedding"}"""))
        assertEquals(
            setOf(ModelPurpose.CHAT, ModelPurpose.EMBEDDING),
            parse("""{"capabilities":["chat","embeddings"]}"""),
        )
        assertEquals(setOf(ModelPurpose.OTHER), parse("""{"capabilities":{"chat":false},"task":"chat"}"""))
        assertEquals(setOf(ModelPurpose.RERANK), parse("""{"endpoints":["rerank"]}"""))
        assertEquals(
            setOf(ModelPurpose.IMAGE, ModelPurpose.AUDIO),
            parse("""{"tasks":["text-to-image","speech-to-text"]}"""),
        )
        assertEquals(
            setOf(ModelPurpose.CHAT),
            parse("""{"capabilities":{"messages":{"supported":true},"embeddings":false}}"""),
        )
    }

    @Test fun generationMethodsAreTasksNotOptionalChatFeatures() {
        assertEquals(
            setOf(ModelPurpose.CHAT),
            parse("""{"supportedGenerationMethods":["generateContent","countTokens"]}"""),
        )
        assertEquals(
            setOf(ModelPurpose.EMBEDDING),
            parse("""{"supportedGenerationMethods":["embedContent","batchEmbedContents"]}"""),
        )
    }

    @Test fun perModelEndpointPathsDeclareTasksWithoutContactingThoseUrls() {
        assertEquals(
            setOf(ModelPurpose.CHAT, ModelPurpose.EMBEDDING),
            parse("""{"endpoints":["https://inference.example/v1/chat/completions?x=1","/v1/embeddings/"]}"""),
        )
        assertEquals(setOf(ModelPurpose.CHAT), parse("""{"endpoints":["/v1/messages"]}"""))
        assertEquals(setOf(ModelPurpose.AUDIO), parse("""{"endpoints":["/v1/audio/speech"]}"""))
        assertEquals(emptySet<ModelPurpose>(), parse("""{"base_url":"https://example/v1/chat/completions"}"""))
    }

    @Test fun mixedDirectoryRetainsAllIdsWithTheirExactTasks() {
        val result =
            WireModelCatalogParser.parseModelIds(
                """{"data":[{"id":"a","capabilities":["chat"]},{"id":"b","type":"embedding"},{"id":"c"}]}""",
            ) as ModelCatalogResult.Listed
        assertEquals(listOf("a", "b", "c"), result.models)
        assertEquals(mapOf("a" to setOf(ModelPurpose.CHAT), "b" to setOf(ModelPurpose.EMBEDDING)), result.purposes)
        assertTrue(WireModelCatalogParser.parseModelIds("""{"data":[],"has_more":true}""") is ModelCatalogResult.Failed)
        assertTrue(
            WireModelCatalogParser.parseModelIds("""{"data":[],"nextPageToken":"more"}""") is ModelCatalogResult.Failed,
        )
    }

    @Test fun siliconFlowClassifiesThroughDocumentedQueriesOnly() =
        runBlocking {
            val wire =
                FixtureWire { url ->
                    when {
                        "sub_type=chat" in url -> """{"data":[{"id":"alias"}]}"""
                        "sub_type=embedding" in url -> """{"data":[{"id":"vector"}]}"""
                        "sub_type=reranker" in url -> """{"data":[{"id":"rank"}]}"""
                        else -> """{"data":[{"id":"alias"},{"id":"vector"},{"id":"rank"},{"id":"picture"}]}"""
                    }
                }
            val result =
                ModelCatalogDiscovery.fetch(
                    wire,
                    "https://api.siliconflow.cn/v1/models",
                    emptyMap(),
                ) as ModelCatalogResult.Listed
            assertEquals(setOf(ModelPurpose.CHAT), result.purposes["alias"])
            assertEquals(setOf(ModelPurpose.EMBEDDING), result.purposes["vector"])
            assertEquals(setOf(ModelPurpose.RERANK), result.purposes["rank"])
            assertEquals(setOf(ModelPurpose.OTHER), result.purposes["picture"])
            assertEquals(4, wire.urls.size)
            assertEquals(4, wire.closed)
            for (url in listOf("https://custom.test/v1/models", "https://api.siliconflow.cn.custom.test/v1/models")) {
                val other = FixtureWire { """{"data":[{"id":"alias"}]}""" }
                val unknown = ModelCatalogDiscovery.fetch(other, url, emptyMap()) as ModelCatalogResult.Listed
                assertTrue(unknown.purposes.isEmpty())
                assertEquals(listOf(url), other.urls)
            }
        }

    @Test fun partialDiscoveryFailureNeverPublishesAllAsChatAndCancellationPropagates() =
        runBlocking {
            val wire = FixtureWire { url -> if ('?' in url) "broken" else """{"data":[{"id":"alias"}]}""" }
            assertTrue(
                ModelCatalogDiscovery.fetch(
                    wire,
                    "https://api.siliconflow.cn/v1/models",
                    emptyMap(),
                ) is ModelCatalogResult.Failed,
            )
            val changed = FixtureWire { url -> if ('?' in url) """{"data":[{"id":"new"}]}""" else """{"data":[]}""" }
            assertTrue(
                ModelCatalogDiscovery.fetch(
                    changed,
                    "https://api.siliconflow.cn/v1/models",
                    emptyMap(),
                ) is ModelCatalogResult.Failed,
            )
            val cancelled = FixtureWire { throw CancellationException("cancelled") }
            try {
                ModelCatalogDiscovery.fetch(cancelled, "https://custom.test/v1/models", emptyMap())
                fail("Cancellation must not become catalog success")
            } catch (_: CancellationException) {
                // expected
            }
        }

    @Test fun paginationMergesIdsAndCapabilitiesWithoutFollowingUrls() =
        runBlocking {
            val wire =
                FixtureWire { url ->
                    if ("after_id=" in url) {
                        """{
                    "data":[{"id":"same","type":"embedding"},{"id":"tail","task":"chat"}],
                    "has_more":false
                }"""
                    } else {
                        """{"data":[{"id":"same"}],"has_more":true,"last_id":"https://other.test/a?b=1"}"""
                    }
                }
            val result =
                ModelCatalogDiscovery.fetch(
                    wire,
                    "https://custom.test/v1/models",
                    mapOf("Authorization" to "synthetic"),
                ) as ModelCatalogResult.Listed
            assertEquals(listOf("same", "tail"), result.models)
            assertEquals(setOf(ModelPurpose.EMBEDDING), result.purposes["same"])
            assertEquals(2, wire.closed)
            val expected = "https://custom.test/v1/models?after_id=https%3A%2F%2Fother.test%2Fa%3Fb%3D1"
            assertEquals(expected, wire.urls.last())
        }

    @Test fun incompleteOrLoopingPaginationNeverReturnsPartialSuccess() =
        runBlocking {
            for (body in listOf(
                """{"data":[{"id":"a"}],"has_more":true,"last_id":"repeat"}""",
                """{"data":[{"id":"a"}],"has_more":true}""",
                """{"data":[],"has_more":"yes"}""",
            )) {
                val wire = FixtureWire { body }
                assertTrue(
                    ModelCatalogDiscovery.fetch(wire, "https://custom.test/v1/models", emptyMap())
                        is ModelCatalogResult.Failed,
                )
                assertTrue(wire.urls.size <= 2)
                assertEquals(wire.urls.size, wire.closed)
            }
            val failed =
                FixtureWire { url ->
                    if ('?' in url) "broken" else """{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}"""
                }
            assertTrue(
                ModelCatalogDiscovery.fetch(failed, "https://custom.test/v1/models", emptyMap())
                    is ModelCatalogResult.Failed,
            )
        }

    private class FixtureWire(
        private val body: (String) -> String,
    ) : WireClient {
        val urls = mutableListOf<String>()
        var closed = 0

        override suspend fun open(request: WireRequest): WireResponse {
            check(request.method == "GET")
            urls += request.url
            val text = body(request.url)
            return WireResponse(
                200,
                emptyMap(),
                object : WireBody {
                    override suspend fun bytes() = text.toByteArray()

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
