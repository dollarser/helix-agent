package com.helix.extensions.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class McpResourcesTest {
    @Test
    fun sdkReadsPaginatedTextAndTemplatesWithoutCallingRemoteTool() =
        runBlocking {
            ResourceFixture().use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                try {
                    assertEquals("page-two", session.listResources().nextCursor)
                    assertEquals(
                        "memo://two",
                        session
                            .listResources("page-two")
                            .entries
                            .single()["uri"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                    val result = McpResourceTools.execute(session, "read_mcp_resource", args("memo://two"))
                    assertTrue(result.structuredContent.toString().contains("source text"))
                    assertTrue(result.structuredContent.toString().contains("contentHash"))
                    assertTrue(result.structuredContent.toString().contains("\"untrusted\":true"))
                    McpResourceTools.execute(session, "read_mcp_resource", args("memo://item/abc"))
                    assertTrue("resources/templates/list" in fixture.methods)
                    assertFalse("tools/call" in fixture.methods)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun wrongSourceAndUnavailablePublisherAndOversizeFailClosed() =
        runBlocking {
            for (mode in listOf(
                "normal",
                "wrong-uri",
                "binary",
                "oversize",
                "invalid-binary",
                "oversize-binary",
                "cumulative-binary",
                "cycle",
                "unsupported",
            )) {
                ResourceFixture(mode).use { fixture ->
                    val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                    try {
                        val uri = if (mode in setOf("normal", "cycle")) "other://missing" else "memo://two"
                        val failure =
                            runCatching {
                                McpResourceTools.execute(
                                    session,
                                    "read_mcp_resource",
                                    args(uri),
                                )
                            }.exceptionOrNull()
                        assertTrue("$mode unexpectedly succeeded", failure != null)
                        if (mode in
                            setOf("normal", "cycle", "unsupported")
                        ) {
                            assertFalse("resources/read" in fixture.methods)
                        }
                    } finally {
                        session.close()
                    }
                }
            }
        }

    @Test
    fun binaryUsesTypedPublisherWithoutBase64InModelResult() =
        runBlocking {
            ResourceFixture("binary").use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                try {
                    val result =
                        McpResourceTools.execute(
                            session,
                            "read_mcp_resource",
                            args("memo://two"),
                            publishBlob = { blob, index ->
                                assertEquals("memo://two", blob.uri)
                                assertEquals("abc", blob.bytes.decodeToString())
                                assertEquals(0, index)
                                assertEquals(
                                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                                    blob.contentHash,
                                )
                                buildJsonObject { put("artifactRef", "verified-local-artifact") }
                            },
                        )
                    assertTrue(result.structuredContent.toString().contains("verified-local-artifact"))
                    assertFalse(result.structuredContent.toString().contains("YWJj"))
                    assertFalse("tools/call" in fixture.methods)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun exactBinaryLimitSucceedsAndPaddingEdgesRejectBeforePublication() =
        runBlocking {
            for (mode in listOf("exact-binary", "edge-one-binary", "edge-two-binary", "late-invalid-binary")) {
                ResourceFixture(mode).use { fixture ->
                    val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                    var published = 0
                    try {
                        val result =
                            runCatching {
                                McpResourceTools.execute(
                                    session,
                                    "read_mcp_resource",
                                    args("memo://two"),
                                    publishBlob = { blob, _ ->
                                        assertEquals(1024 * 1024, blob.bytes.size)
                                        published++
                                        buildJsonObject { put("status", "REGISTERED") }
                                    },
                                )
                            }
                        assertEquals(mode, mode == "exact-binary", result.isSuccess)
                        assertEquals(mode, if (mode == "exact-binary") 1 else 0, published)
                    } finally {
                        session.close()
                    }
                }
            }
        }

    @Test
    fun savedButUnregisteredBlobReturnsAnErrorWithRecoveryReference() =
        runBlocking {
            ResourceFixture("binary").use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                try {
                    val result =
                        McpResourceTools.execute(
                            session,
                            "read_mcp_resource",
                            args("memo://two"),
                            publishBlob = { _, _ ->
                                buildJsonObject {
                                    put("status", "SAVED_NOT_REGISTERED")
                                    put("path", "scope:app/output/saved.bin")
                                }
                            },
                        )
                    assertTrue(result.isError)
                    assertTrue(result.structuredContent.toString().contains("saved.bin"))
                    assertFalse(result.structuredContent.toString().contains("artifactRef"))
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun revokedAfterReadNeverPublishesBinary() =
        runBlocking {
            ResourceFixture("binary").use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                var published = false
                try {
                    assertTrue(
                        runCatching {
                            McpResourceTools.execute(
                                session,
                                "read_mcp_resource",
                                args("memo://two"),
                                publishBlob = { _, _ ->
                                    published = true
                                    buildJsonObject {}
                                },
                                checkCurrent = { check("resources/read" !in fixture.methods) },
                            )
                        }.isFailure,
                    )
                    assertFalse(published)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun revokedSourceDoesNotReadAfterDiscovery() =
        runBlocking {
            ResourceFixture().use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                try {
                    val failure =
                        runCatching {
                            McpResourceTools.execute(session, "read_mcp_resource", args("memo://two")) {
                                check("resources/list" !in fixture.methods) { "revoked" }
                            }
                        }.exceptionOrNull()
                    assertTrue(failure != null)
                    assertFalse("resources/read" in fixture.methods)
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun closedSessionAndInvalidUrisDoNotReachServer() =
        runBlocking {
            ResourceFixture().use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                assertTrue(runCatching { session.readResource("../local") }.isFailure)
                session.close()
                assertTrue(runCatching { session.listResources() }.isFailure)
                assertFalse("resources/read" in fixture.methods)
            }
            assertTrue(matchesSimpleTemplate("memo://item/{id}", "memo://item/a%20b"))
            assertFalse(matchesSimpleTemplate("memo://item/{+path}", "memo://item/a/b"))
            assertFalse(matchesSimpleTemplate("memo://item/{id}", "memo://other/a"))
        }

    @Test
    fun resourceReadDeadlineCancelsWithoutRemoteToolReplay() =
        runBlocking {
            ResourceFixture("slow").use { fixture ->
                val session = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                try {
                    val failure =
                        runCatching {
                            kotlinx.coroutines.withTimeout(300) { session.readResource("memo://two") }
                        }.exceptionOrNull()
                    assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
                    assertEquals(1, fixture.methods.count { it == "resources/read" })
                } finally {
                    session.close()
                }
            }
        }

    @Test
    fun hostContinuationReplaysWithFreshSessionCursorAndRejectsAnotherSource() =
        runBlocking {
            ResourceFixture("session-cursor").use { fixture ->
                val first = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                val cursor =
                    try {
                        McpResourceTools
                            .execute(first, "list_mcp_resources", buildJsonObject {}, "source-a")
                            .structuredContent!!
                            .getValue("resource")
                            .jsonObject
                            .getValue("nextCursor")
                            .jsonPrimitive.content
                    } finally {
                        first.close()
                    }
                val second = McpClients.sdk("resource-test", "1").connect(fixture.endpoint)
                try {
                    val arguments = buildJsonObject { put("cursor", cursor) }
                    assertTrue(
                        runCatching {
                            McpResourceTools.execute(second, "list_mcp_resources", arguments, "source-b")
                        }.isFailure,
                    )
                    val result = McpResourceTools.execute(second, "list_mcp_resources", arguments, "source-a")
                    assertTrue(result.structuredContent.toString().contains("memo://two"))
                    assertEquals(listOf("page-2"), fixture.cursors)
                } finally {
                    second.close()
                }
            }
        }

    @Test(timeout = 2000)
    fun maliciousAdjacentAndRepeatedVariablesHaveBoundedMatchingCost() =
        runBlocking {
            assertFalse(matchesSimpleTemplate("memo://" + "{x}".repeat(500) + "Z", "memo://" + "a".repeat(3000)))
            assertFalse(matchesSimpleTemplate("memo://" + "{x}a".repeat(400) + "Z", "memo://" + "a".repeat(3000)))
            assertTrue(matchesSimpleTemplate("memo://{a}{b}", "memo://xy"))
        }

    private fun args(uri: String): JsonObject = buildJsonObject { put("uri", uri) }
}

private class ResourceFixture(
    private val mode: String = "normal",
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val methods = CopyOnWriteArrayList<String>()
    val cursors = CopyOnWriteArrayList<String>()
    private var generation = 0
    val endpoint: String

    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            exchange.use {
                if (exchange.requestMethod != "POST") {
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    if (method == "initialize") generation++
                    val id = request["id"]
                    if (id == null) {
                        exchange.sendResponseHeaders(202, -1)
                    } else {
                        val params = request["params"]?.jsonObject
                        params
                            ?.get("cursor")
                            ?.jsonPrimitive
                            ?.content
                            ?.let { cursors += it }
                        val result =
                            when (method) {
                                "initialize" -> {
                                    val capabilities = if (mode == "unsupported") "{}" else "{\"resources\":{}}"
                                    """{"protocolVersion":"2025-03-26","capabilities":$capabilities,""" +
                                        """"serverInfo":{"name":"fixture","version":"1"}}"""
                                }

                                "resources/list" -> {
                                    if (params?.get("cursor") == null || mode == "cycle") {
                                        val next = if (mode == "session-cursor") "page-$generation" else "page-two"
                                        """{"resources":[{"uri":"memo://one","name":"One"}],"nextCursor":"$next"}"""
                                    } else {
                                        """{"resources":[{"uri":"memo://two","name":"Two"}]}"""
                                    }
                                }

                                "resources/templates/list" -> {
                                    """{"resourceTemplates":[{"uriTemplate":"memo://item/{id}","name":"Item"}]}"""
                                }

                                "resources/read" -> {
                                    if (mode == "slow") Thread.sleep(1500)
                                    val uri =
                                        if (mode ==
                                            "wrong-uri"
                                        ) {
                                            "memo://wrong"
                                        } else {
                                            params!!.getValue("uri").jsonPrimitive.content
                                        }
                                    val content =
                                        buildJsonObject {
                                            put("uri", uri)
                                            put("mimeType", "text/plain")
                                            if (mode.endsWith("binary")) {
                                                put(
                                                    "blob",
                                                    when (mode) {
                                                        "invalid-binary" -> {
                                                            "YR=="
                                                        }

                                                        "oversize-binary" -> {
                                                            "AAAA".repeat(350000)
                                                        }

                                                        "cumulative-binary" -> {
                                                            "AAAA".repeat(180000)
                                                        }

                                                        "exact-binary", "edge-one-binary", "edge-two-binary" -> {
                                                            val extra =
                                                                when (mode) {
                                                                    "edge-one-binary" -> 1
                                                                    "edge-two-binary" -> 2
                                                                    else -> 0
                                                                }
                                                            java.util.Base64
                                                                .getEncoder()
                                                                .encodeToString(ByteArray(1024 * 1024 + extra))
                                                        }

                                                        else -> {
                                                            "YWJj"
                                                        }
                                                    },
                                                )
                                            } else {
                                                put(
                                                    "text",
                                                    if (mode ==
                                                        "oversize"
                                                    ) {
                                                        "界".repeat(100000)
                                                    } else {
                                                        "source text"
                                                    },
                                                )
                                            }
                                        }
                                    if (mode == "late-invalid-binary") {
                                        """{"contents":[$content,{"uri":"$uri","blob":"!!!!"}]}"""
                                    } else if (mode == "cumulative-binary") {
                                        """{"contents":[$content,$content]}"""
                                    } else {
                                        """{"contents":[$content]}"""
                                    }
                                }

                                else -> {
                                    "{}"
                                }
                            }
                        val response = """{"jsonrpc":"2.0","id":$id,"result":$result}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.write(response)
                    }
                }
            }
        }
        server.start()
        endpoint = "http://127.0.0.1:${server.address.port}/mcp"
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
