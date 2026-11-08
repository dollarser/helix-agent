package com.helix.tools.android

import com.helix.core.model.CancelSignal
import com.helix.core.model.NoCancellation
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class WebFetchTransportTest {
    @Test
    fun urlKeepsQueryDropsFragmentAndRejectsCredentialsOrNonWebSchemes() {
        assertEquals(
            "https://example.com/a?q=one%20two",
            webFetchUrl("https://example.com/a?q=one%20two#part").toString(),
        )
        for (url in listOf(
            "file:///tmp/a",
            "https:example.com",
            "https:///example.com",
            "//example.com/",
            "https://name:password@example.com",
            "https://@example.com",
            "https://example.com/\n",
        )) {
            assertThrows(WebFetchFailure::class.java) { webFetchUrl(url) }
        }
    }

    @Test
    fun invalidPolicyHostsFailBeforeDnsWithoutLeakingConstructorExceptions() {
        val neverResolve =
            object : AddressResolver {
                override fun resolve(host: String): List<ByteArray> = error("must not resolve")
            }
        val transport = transport(AtomicReference(EgressPolicy(SafetyProfile.STANDARD, emptySet())), neverResolve)
        for (url in listOf("https://bad_host.example/", "https://-bad.example/", "https://example..com/")) {
            assertEquals(
                "invalid-url",
                assertThrows(WebFetchFailure::class.java) {
                    transport.fetch(url, deadline(), NoCancellation)
                }.code,
            )
        }
    }

    @Test
    fun redirectRejectsRawCredentialsControlsInvalidHostsAndDowngrades() {
        val base = webFetchUrl("https://example.com/source")
        for ((location, code) in listOf(
            "//user:secret@example.com/" to "credentials-in-url",
            "//@example.com/" to "credentials-in-url",
            "https://@example.com/" to "credentials-in-url",
            "https://bad_host.example/" to "invalid-url",
            "http://example.com/" to "https-downgrade",
            "file:///tmp/source" to "invalid-redirect",
            "/bad\npath" to "invalid-redirect",
            "/bad\\path" to "invalid-redirect",
            "" to "invalid-redirect",
            "/" + "a".repeat(WEB_URL_LIMIT) to "invalid-redirect",
        )) {
            assertEquals(code, assertThrows(WebFetchFailure::class.java) { webFetchRedirect(base, location) }.code)
        }
        assertEquals("https://example.com/next?q=1", webFetchRedirect(base, "/next?q=1#part").toString())
    }

    @Test
    fun unsupportedOrStackedContentEncodingsNeverBecomeReadableText() {
        for (encodings in listOf(
            listOf("br"),
            listOf("deflate"),
            listOf("gzip, br"),
            listOf("identity, gzip"),
            listOf("br", "gzip"),
            listOf("gzip", "identity"),
            listOf("gzip", "gzip"),
        )) {
            Fixture { exchange ->
                encodings.forEach { exchange.responseHeaders.add("Content-Encoding", it) }
                respond(exchange, "not decoded text".toByteArray())
            }.use { server ->
                assertEquals(
                    "unsupported-content-encoding",
                    assertThrows(WebFetchFailure::class.java) { fetch(server) }.code,
                )
            }
        }
    }

    @Test
    fun encodedErrorAndBinaryResponsesAreNotConsumedAsDocuments() {
        for ((status, type) in listOf(403 to "text/plain", 200 to "application/pdf")) {
            Fixture { exchange ->
                exchange.responseHeaders.add("Content-Encoding", "br")
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(status, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            }.use { server ->
                val page = fetch(server)
                assertEquals(status, page.status)
                assertEquals(type, page.mediaType)
                assertTrue(page.bytes.isEmpty())
                assertFalse(page.truncated)
            }
        }
    }

    @Test
    fun identityAndSingleGzipAreDecodedAndCorruptGzipFails() {
        for (encoding in listOf("identity", "GZip")) {
            val bytes = "decoded text".toByteArray()
            val payload =
                if (encoding == "identity") {
                    bytes
                } else {
                    ByteArrayOutputStream()
                        .also { output ->
                            GZIPOutputStream(output).use { it.write(bytes) }
                        }.toByteArray()
                }
            Fixture { exchange ->
                exchange.responseHeaders.add("Content-Encoding", encoding)
                respond(exchange, payload)
            }.use { server ->
                assertEquals("decoded text", fetch(server).bytes.toString(Charsets.UTF_8))
            }
        }
        Fixture { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            respond(exchange, "not gzip".toByteArray())
        }.use { server ->
            assertThrows(IOException::class.java) { fetch(server) }
        }
    }

    @Test
    fun getUsesQueryAndDoesNotFollowLocationOnSuccess() {
        Fixture { exchange ->
            assertEquals("q=one%20two", exchange.requestURI.rawQuery)
            assertEquals(null, exchange.requestHeaders.getFirst("Cookie"))
            assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Location", "http://169.254.169.254/")
            respond(exchange, "hello".toByteArray())
        }.use { server ->
            val page = fetch(server, server.url + "?q=one%20two#fragment")
            assertEquals("hello", page.bytes.toString(Charsets.UTF_8))
            assertEquals(0, page.redirects)
            assertFalse(page.truncated)
        }
    }

    @Test
    fun compressedChunkedBodyIsLimitedAfterDecompression() {
        val compressed =
            ByteArrayOutputStream()
                .also { output ->
                    GZIPOutputStream(output).use { it.write(ByteArray(WEB_BODY_LIMIT * 16) { 65 }) }
                }.toByteArray()
        Fixture { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            respond(exchange, compressed, chunked = true)
        }.use { server ->
            val page = fetch(server)
            assertEquals(WEB_BODY_LIMIT, page.bytes.size)
            assertTrue(page.truncated)
            assertEquals(65.toByte(), page.bytes.first())
        }
    }

    @Test
    fun exactBodyLimitIsNotTruncation() {
        Fixture { respond(it, ByteArray(WEB_BODY_LIMIT) { 65 }, chunked = true) }.use { server ->
            assertFalse(fetch(server).truncated)
        }
    }

    @Test
    fun relativeRedirectKeepsActualFinalUrlAndLoopHasBoundedHops() {
        Fixture { exchange ->
            if (exchange.requestURI.path == "/") {
                exchange.responseHeaders.add("Location", "/final?q=kept#fragment")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                respond(exchange, "source".toByteArray())
            }
        }.use { server ->
            val page = fetch(server)
            assertEquals(server.url + "final?q=kept", page.url)
            assertEquals(1, page.redirects)
        }
        Fixture { exchange ->
            exchange.responseHeaders.add("Location", "/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { server ->
            assertEquals(
                "redirect-limit",
                assertThrows(WebFetchFailure::class.java) { fetch(server) }.code,
            )
            assertEquals(WEB_REDIRECT_LIMIT + 1, server.requests.get())
        }
    }

    @Test
    fun cancellationClosesAStalledBody() {
        val cancelled = AtomicBoolean(false)
        val cancel =
            object : CancelSignal {
                override fun isCancelled() = cancelled.get()
            }
        Fixture { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 1000)
            exchange.responseBody.write(65)
            exchange.responseBody.flush()
            cancelled.set(true)
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.use { server ->
            val transport = transport(AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(server)))))
            val start = System.nanoTime()
            assertThrows(IOException::class.java) { transport.fetch(server.url, deadline(), cancel) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
        }
    }

    @Test
    fun redirectRechecksScopeAndCannotAcquireNewScope() {
        Fixture { respond(it, "target".toByteArray()) }.use { target ->
            val live = AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, emptySet()))
            Fixture { exchange ->
                live.set(EgressPolicy(SafetyProfile.ADVANCED, live.get().lanScopes + scope(target)))
                exchange.responseHeaders.add("Location", target.url)
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }.use { source ->
                live.set(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(source))))
                val transport = transport(live)
                assertEquals(
                    "network-scope_violation",
                    assertThrows(WebFetchFailure::class.java) {
                        transport.fetch(source.url, deadline(), NoCancellation)
                    }.code,
                )
                assertEquals(0, target.requests.get())
            }
        }
    }

    @Test
    fun mixedDnsAndRevocationFailBeforeAnyHttpRequest() {
        Fixture { respond(it, byteArrayOf(65)) }.use { server ->
            val live = AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(server))))
            val resolver =
                object : AddressResolver {
                    override fun resolve(host: String): List<ByteArray> {
                        live.set(EgressPolicy(SafetyProfile.STANDARD, emptySet()))
                        return listOf(LOOPBACK)
                    }
                }
            assertThrows(
                WebFetchFailure::class.java,
            ) { transport(live, resolver).fetch(server.url, deadline(), NoCancellation) }
            assertEquals(0, server.requests.get())
            live.set(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(server))))
            val mixed =
                object : AddressResolver {
                    override fun resolve(host: String) =
                        listOf(LOOPBACK, byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))
                }
            assertThrows(
                WebFetchFailure::class.java,
            ) { transport(live, mixed).fetch(server.url, deadline(), NoCancellation) }
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun cancellationDuringDnsPreventsSendingAfterLookupReturns() {
        val cancelled = AtomicBoolean(false)
        Fixture { respond(it, byteArrayOf(65)) }.use { server ->
            val live = AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(server))))
            val resolver =
                object : AddressResolver {
                    override fun resolve(host: String): List<ByteArray> {
                        cancelled.set(true)
                        return listOf(LOOPBACK)
                    }
                }
            val cancel =
                object : CancelSignal {
                    override fun isCancelled() = cancelled.get()
                }
            assertEquals(
                "cancelled",
                assertThrows(WebFetchFailure::class.java) {
                    transport(live, resolver).fetch(server.url, deadline(), cancel)
                }.code,
            )
            assertEquals(0, server.requests.get())
        }
    }

    @Test
    fun continuousSlowBodyStillStopsAtTheWholeCallDeadline() {
        Fixture { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 1000)
            try {
                exchange.responseBody.use { output ->
                    repeat(1000) {
                        output.write(65)
                        output.flush()
                        Thread.sleep(20)
                    }
                }
            } catch (_: IOException) {
                // The client closes its connection on deadline.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.use { server ->
            val transport = transport(AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(server)))))
            val start = System.nanoTime()
            assertThrows(
                IOException::class.java,
            ) { transport.fetch(server.url, System.currentTimeMillis() + 250, NoCancellation) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000)
        }
    }

    private fun fetch(
        server: Fixture,
        url: String = server.url,
    ): WebFetchPage =
        transport(AtomicReference(EgressPolicy(SafetyProfile.ADVANCED, setOf(scope(server)))))
            .fetch(url, deadline(), NoCancellation)

    private fun transport(
        live: AtomicReference<EgressPolicy>,
        resolver: AddressResolver =
            object : AddressResolver {
                override fun resolve(host: String) = listOf(LOOPBACK)
            },
    ) = PinnedWebFetchTransport(
        object : EgressPolicyProvider {
            override fun current() = live.get()
        },
        resolver,
    )

    private fun scope(server: Fixture) = NetworkOriginScope("localhost", server.port)

    private fun deadline() = System.currentTimeMillis() + 10_000

    private class Fixture(
        handler: (HttpExchange) -> Unit,
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val port get() = server.address.port
        val url get() = "http://localhost:$port/"

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                handler(exchange)
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        val LOOPBACK = byteArrayOf(127, 0, 0, 1)

        fun respond(
            exchange: HttpExchange,
            bytes: ByteArray,
            chunked: Boolean = false,
        ) {
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
