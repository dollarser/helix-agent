package com.helix.extensions.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class McpResponseWitnessHttpTest {
    @Test(timeout = 10000)
    fun actualSdkOptionsAndWireIdsProduceEvidenceForEachResourceMethodAndTool() =
        runBlocking {
            WitnessHttpFixture().use { fixture ->
                val session = McpClients.sdk("witness-test", "1").connect(fixture.endpoint)
                try {
                    val operations: List<suspend () -> Any> =
                        listOf(
                            { session.listResources() },
                            { session.listResourceTemplates() },
                            { session.readResource("memo://one") },
                            { session.callTool("test", buildJsonObject {}, McpToolResultLimits()) },
                        )
                    operations.forEach { operation ->
                        val failure = runCatching { operation() }.exceptionOrNull()
                        assertTrue("Unexpected failure: $failure", failure is McpProtocolResponseException)
                        assertEquals(12345, (failure as McpProtocolResponseException).code)
                        assertFalse(failure.message!!.contains("server-secret"))
                    }
                    fixture.transportFailure = true
                    val noReply = runCatching { session.listResources() }.exceptionOrNull()
                    assertTrue(noReply != null)
                    assertFalse(noReply is McpProtocolResponseException)
                } finally {
                    session.close()
                }
            }
        }
}

private class WitnessHttpFixture : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile var transportFailure = false
    val endpoint: String

    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            exchange.use {
                if (exchange.requestMethod != "POST") {
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    val id = request["id"]
                    if (id == null) {
                        exchange.sendResponseHeaders(202, -1)
                    } else if (transportFailure) {
                        exchange.sendResponseHeaders(503, -1)
                    } else {
                        val field =
                            if (request["method"]?.jsonPrimitive?.content == "initialize") {
                                """"result":{"protocolVersion":"2025-03-26",""" +
                                    """"capabilities":{"resources":{},"tools":{}},""" +
                                    """"serverInfo":{"name":"fixture","version":"1"}}"""
                            } else {
                                """"error":{"code":12345,"message":"server-secret"}"""
                            }
                        val body = """{"jsonrpc":"2.0","id":$id,$field}""".toByteArray()
                        exchange.responseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
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
