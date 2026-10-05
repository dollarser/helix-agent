package com.helix.runtime.cli.app

import com.helix.core.policy.network.NativeDnsSettings
import com.helix.core.policy.network.NativeNetwork
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real loopback connections; no public DNS, subscription account or external service. */
class SubscriptionDnsConnectionTest {
    @Test fun existingClientUsesSavedMappingAndFallsBackAfterRemoval() {
        var stored: String? = null

        val settings = NativeDnsSettings({ stored }, { stored = it })
        val previous = NativeNetwork.settings
        val resolver =
            com.helix.core.policy.network
                .NativeDnsResolver({ throw UnknownHostException(it) })
        val client =
            OkHttpClient
                .Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(Dns { resolver.lookup(it) })
                .callTimeout(3, TimeUnit.SECONDS)
                .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            NativeNetwork.settings = settings
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 3000
                val request = Request.Builder().url("http://subscription.invalid:${server.localPort}/models").build()
                assertThrows(UnknownHostException::class.java) { client.newCall(request).execute().close() }
                settings.save("127.0.0.1 subscription.invalid")
                val received =
                    executor.submit<List<String>> {
                        server.accept().use { socket ->
                            socket.soTimeout = 3000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray(),
                            )
                            headers
                        }
                    }
                client.newCall(request).execute().use { assertEquals("OK", it.body.string()) }
                val headers = received.get(3, TimeUnit.SECONDS)
                assertEquals("GET /models HTTP/1.1", headers.first())
                assertEquals("Host: subscription.invalid:${server.localPort}", headers.first { it.startsWith("Host:") })
                settings.save("")
                assertThrows(UnknownHostException::class.java) { client.newCall(request).execute().close() }
                settings.save("127.0.0.1 subscription.invalid")
                settings.save("")
                assertThrows(UnknownHostException::class.java) { client.newCall(request).execute().close() }
            }
        } finally {
            NativeNetwork.settings = previous
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
