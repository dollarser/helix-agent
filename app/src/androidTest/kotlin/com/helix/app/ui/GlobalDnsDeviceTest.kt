package com.helix.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.HelixTheme
import com.helix.app.network.NativeDnsProbeService
import com.helix.app.network.NetworkSettingsActivity
import com.helix.core.policy.network.NativeNetwork
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic loopback only. Saves/removes one reserved mapping; never changes existing user entries. */
class GlobalDnsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<NetworkSettingsActivity>()

    @Test fun generalSettingsOpensTheSharedPage() {
        compose.runOnUiThread { compose.activity.setContent { HelixTheme { SettingsScreen() } } }
        compose.onNodeWithTag("settings-network").performScrollTo().performClick()
        compose.onNodeWithTag("screen-network").assertIsDisplayed()
        compose.onNodeWithTag("dns-hosts").assertIsDisplayed()
        androidx.test.espresso.Espresso
            .pressBack()
    }

    @Test fun invalidHostsCannotReplaceSavedConfiguration() {
        val settings = requireNotNull(NativeNetwork.settings)
        val original = settings.text()
        compose.onNodeWithTag("dns-hosts").performTextReplacement("127.0.0.1 good.invalid\nnot-an-ip bad.invalid")
        compose.onNodeWithTag("dns-save").performScrollTo().assertIsNotEnabled()
        assertEquals(original, settings.text())
    }

    @Test fun uiSaveConnectsAndUpdatesTheAlreadyRunningSubscriptionProcess() {
        val context = compose.activity
        val settings = requireNotNull(NativeNetwork.settings)
        val original = settings.text()
        check(settings.lookup(HOST) == null) { "Reserved fixture hostname already configured" }
        val connected = ArrayBlockingQueue<Messenger>(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    binder: IBinder,
                ) {
                    connected.offer(Messenger(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        assertTrue(
            context.bindService(
                Intent(context, NativeDnsProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            val remote = requireNotNull(connected.poll(10, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), probe(remote))
            compose.onNodeWithTag("dns-hosts").performTextReplacement("$original\n127.0.0.1 $HOST")
            compose.onNodeWithTag("dns-save").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf("127.0.0.1"), probe(remote))
            assertEquals("127.0.0.1", NativeNetwork.resolve(HOST).single().hostAddress)
            assertWireConnection()
            compose.onNodeWithTag("dns-hosts").performScrollTo().performTextReplacement("$original\n192.0.2.1 $HOST")
            compose.onNodeWithTag("dns-save").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf("192.0.2.1"), probe(remote))
            settings.save(original)
            assertEquals(emptyList<String>(), probe(remote))
        } finally {
            settings.save(original)
            context.unbindService(connection)
        }
    }

    private fun probe(remote: Messenger): List<String> {
        val results = ArrayBlockingQueue<android.os.Bundle>(1)
        remote.send(
            Message.obtain().apply {
                replyTo =
                    Messenger(
                        Handler(Looper.getMainLooper()) { result ->
                            results.offer(result.data)
                            true
                        },
                    )
            },
        )
        val result = requireNotNull(results.poll(5, TimeUnit.SECONDS))
        assertNotEquals(android.os.Process.myPid(), result.getInt("pid"))
        return requireNotNull(result.getStringArrayList("addresses"))
    }

    private fun assertWireConnection() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val incoming =
                    executor.submit<String> {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray(),
                            )
                            headers.first { it.startsWith("Host:") }
                        }
                    }
                runBlocking {
                    val response =
                        OkHttpWireClient().open(
                            WireRequest(
                                "GET",
                                "http://$HOST:${server.localPort}/",
                                emptyMap(),
                                null,
                            ),
                        )
                    try {
                        assertEquals("OK", response.body.bytes().decodeToString())
                    } finally {
                        response.body.close()
                    }
                }
                assertEquals("Host: $HOST:${server.localPort}", incoming.get(5, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val HOST = "helix-dns-fixture.invalid"
    }
}
