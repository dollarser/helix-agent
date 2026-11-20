package com.helix.provider.api.wire

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Slow local uploads exercise write deadlines without external services or credentials. */
class OkHttpUploadTimeoutTest {
    @Test fun configuredWriteDeadlineAllowsSlowUploadWithoutReplay() {
        withSlowReceiver { url, requests, _ ->
            val response =
                runBlocking {
                    OkHttpWireClient(writeTimeoutMillis = 5_000).open(upload(url))
                }
            response.body.close()
            assertEquals(200, response.status)
            assertEquals(1, requests.get())
        }
    }

    @Test fun shortWriteDeadlineFailsOnceWithoutReplay() {
        withSlowReceiver { url, requests, _ ->
            assertThrows(IOException::class.java) {
                runBlocking { OkHttpWireClient(writeTimeoutMillis = 100).open(upload(url)) }
            }
            assertEquals(1, requests.get())
        }
    }

    @Test fun cancellationStillInterruptsUploadWithLongWriteDeadline() {
        withSlowReceiver { url, requests, entered ->
            runBlocking {
                val pending = async { OkHttpWireClient(writeTimeoutMillis = 180_000).open(upload(url)) }
                withTimeout(2_000) {
                    while (entered.count != 0L) delay(10)
                }
                pending.cancel()
                withTimeout(2_000) { pending.join() }
                assertTrue(pending.isCancelled)
            }
            assertEquals(1, requests.get())
        }
    }

    private fun upload(url: String) =
        WireRequest("POST", url, mapOf("Content-Type" to "application/octet-stream"), ByteArray(16 * 1024 * 1024))

    private fun withSlowReceiver(action: (String, AtomicInteger, CountDownLatch) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        server.createContext("/upload") { exchange ->
            requests.incrementAndGet()
            entered.countDown()
            try {
                Thread.sleep(600)
                exchange.requestBody.transferTo(java.io.OutputStream.nullOutputStream())
                exchange.sendResponseHeaders(200, -1)
            } catch (_: IOException) {
                // The timeout/cancellation cases deliberately close their single upload.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            action("http://127.0.0.1:${server.address.port}/upload", requests, entered)
        } finally {
            server.stop(0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        }
    }
}
