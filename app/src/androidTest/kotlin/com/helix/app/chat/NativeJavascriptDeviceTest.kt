package com.helix.app.chat

import android.os.Process
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.runtime.quickjs.JsCancellation
import com.helix.runtime.quickjs.JsExecuteParams
import com.helix.runtime.quickjs.JsExecutionClient
import com.helix.runtime.quickjs.JsExecutionLimits
import com.helix.runtime.quickjs.JsExecutionStatus
import com.helix.runtime.quickjs.JsNativeExecutionService
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Owner-authorized native access: real app-UID process, local fixtures, no real accounts. */
class NativeJavascriptDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val client get() = JsExecutionClient(context)
    private var originalNativeAccess = false

    @Before fun enableNativeFixture() {
        val settings =
            com.helix.runtime.quickjs
                .JsNativeAccessSettings(context)
        originalNativeAccess = settings.enabled
        settings.setEnabled(true)
    }

    @After fun restoreNativeGrant() {
        com.helix.runtime.quickjs
            .JsNativeAccessSettings(context)
            .setEnabled(originalNativeAccess)
    }

    @Test fun disabledNativeAccessCannotBeRequestedByModelArguments() {
        com.helix.runtime.quickjs
            .JsNativeAccessSettings(context)
            .setEnabled(false)
        val result = client.execute(params("return 42"))
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertTrue(result.detail.contains("QUICKJS_NATIVE_ACCESS_DISABLED"))
    }

    @Test fun reEnablingDoesNotRenewAnEarlierExecutionGrant() {
        val settings =
            com.helix.runtime.quickjs
                .JsNativeAccessSettings(context)
        val revision = settings.revision
        assertTrue(settings.allows(revision))
        settings.setEnabled(false)
        settings.setEnabled(true)
        assertFalse(settings.allows(revision))
        assertTrue(settings.allows(settings.revision))
    }

    private fun params(
        source: String,
        timeout: Long = 10_000,
    ) = JsExecuteParams(
        UUID.randomUUID().toString(),
        source,
        limits = JsExecutionLimits(timeoutMs = timeout),
        nativeAccess = true,
    )

    @Test fun nativeSuccessReturnsOnlyAfterOriginalBinderDeath() {
        assertNativeDeathBeforeReturn(params("return 42;"), JsExecutionStatus.SUCCESS)
    }

    @Test fun blockedNativeCallReturnsOnlyAfterOriginalBinderDeath() {
        assertNativeDeathBeforeReturn(
            params("native.java.staticCall('java.lang.Thread','sleep',['long'],['30000']); return 1;"),
            JsExecutionStatus.TIMEOUT,
        )
    }

    private fun assertNativeDeathBeforeReturn(
        request: JsExecuteParams,
        expected: JsExecutionStatus,
    ) {
        val connected = java.util.concurrent.CountDownLatch(1)
        val original = AtomicReference<android.os.IBinder?>()
        val connection =
            object : android.content.ServiceConnection {
                override fun onServiceConnected(
                    name: android.content.ComponentName,
                    service: android.os.IBinder,
                ) {
                    original.compareAndSet(null, service)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
            }
        val bound =
            context.bindService(
                android.content.Intent(context, JsNativeExecutionService::class.java),
                android.content.Context.BIND_AUTO_CREATE,
                context.mainExecutor,
                connection,
            )
        assertTrue(bound)
        try {
            assertTrue(connected.await(15, java.util.concurrent.TimeUnit.SECONDS))
            val binder = requireNotNull(original.get())
            val result = client.execute(request)
            assertEquals(result.detail, expected, result.status)
            // Another observer still holds a binding; unbind/reclamation alone cannot satisfy this.
            assertFalse("Original native Binder is still alive when execute returns", binder.isBinderAlive)
        } finally {
            context.unbindService(connection)
        }
    }

    @Test fun nativeFilesAndAndroidRunInPrivateAppUidProcess() {
        val file = File(context.cacheDir, "native-${UUID.randomUUID()}.txt")
        try {
            val source =
                """
                const path = ${JsonPrimitive(file.absolutePath)};
                native.files.writeText(path, 'native fixture');
                const text = native.files.readText(path);
                const name = native.java.call(native.android.context, 'getPackageName');
                return {text, name};
                """.trimIndent()
            val result = client.execute(params(source))
            assertEquals(result.detail, JsExecutionStatus.SUCCESS, result.status)
            assertEquals(Process.myUid(), result.serviceUid)
            assertTrue(result.servicePid != Process.myPid())
            assertEquals("native fixture", file.readText())
            assertTrue(result.outputUtf8.toString(Charsets.UTF_8).contains(context.packageName))
            val isolated = client.execute(params("return typeof native").copy(nativeAccess = false))
            assertEquals(JsExecutionStatus.SUCCESS, isolated.status)
            assertEquals("\"undefined\"", isolated.outputUtf8.toString(Charsets.UTF_8))
        } finally {
            file.delete()
        }
    }

    @Test fun nativeHttpHonorsChannelCleartextPolicy() {
        if (!android.security.NetworkSecurityPolicy
                .getInstance()
                .isCleartextTrafficPermitted("127.0.0.1")
        ) {
            val rejected = client.execute(params("return native.net.request('http://127.0.0.1:9/');"))
            assertEquals(rejected.detail, JsExecutionStatus.JS_ERROR, rejected.status)
            assertTrue(rejected.detail.contains("Cleartext HTTP"))
            return
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 20_000
            val failure = AtomicReference<Throwable?>()
            val worker =
                thread {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { /* Drain headers. */ }
                            val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            socket.getOutputStream().write(response.toByteArray())
                        }
                    } catch (error: Exception) {
                        failure.set(error)
                    }
                }
            val source = "return native.net.request('http://127.0.0.1:${server.localPort}/');"
            val result = client.execute(params(source))
            worker.join(21_000)
            assertEquals(result.detail, JsExecutionStatus.SUCCESS, result.status)
            assertEquals(null, failure.get())
            assertEquals("{\"status\":200,\"body\":\"ok\"}", result.outputUtf8.toString(Charsets.UTF_8))
        }
    }

    @Test fun nativeSocketHasNetworkAccessInBothChannels() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 15_000
            val failure = AtomicReference<Throwable?>()
            val worker =
                thread {
                    try {
                        server.accept().use { it.getOutputStream().write("ok".toByteArray()) }
                    } catch (error: Exception) {
                        failure.set(error)
                    }
                }
            val source =
                """
                const socket = native.java.create('java.net.Socket', ['java.lang.String','int'],
                    ['127.0.0.1', ${server.localPort}]);
                const input = native.java.call(socket, 'getInputStream');
                const first = native.java.call(input, 'read');
                native.java.call(socket, 'close');
                return first;
                """.trimIndent()
            val result = client.execute(params(source))
            worker.join(16_000)
            assertEquals(result.detail, JsExecutionStatus.SUCCESS, result.status)
            assertEquals(null, failure.get())
            assertEquals("111", result.outputUtf8.toString(Charsets.UTF_8))
        }
    }

    @Test fun androidPermissionsStillRejectUnavailablePrivilege() {
        val source =
            """
            native.java.call(native.android.context, 'enforcePermission',
              ['java.lang.String','int','int','java.lang.String'],
              ['android.permission.REBOOT',${Process.myPid()},${Process.myUid()},'fixture']);
            return 'unexpected';
            """.trimIndent()
        val result = client.execute(params(source))
        assertEquals(result.detail, JsExecutionStatus.JS_ERROR, result.status)
    }

    @Test fun blockingNativeCallTimesOutAndNextExecutionIsFresh() {
        val marker = File(context.cacheDir, "native-timeout-${UUID.randomUUID()}")
        try {
            val source =
                """
                native.files.writeText(${JsonPrimitive(marker.absolutePath)}, 'started');
                native.java.staticCall('java.lang.Thread','sleep',['long'],['30000']);
                native.files.writeText(${JsonPrimitive(marker.absolutePath)}, 'late');
                return 1;
                """.trimIndent()
            // Include cold service startup, but still expire well before the blocking API returns.
            val result = client.execute(params(source, 10_000))
            assertEquals(result.detail, JsExecutionStatus.TIMEOUT, result.status)
            assertEquals("started", marker.readText())
            val next = client.execute(params("return typeof leaked;"))
            assertEquals(next.detail, JsExecutionStatus.SUCCESS, next.status)
            assertEquals("\"undefined\"", next.outputUtf8.toString(Charsets.UTF_8))
            assertEquals("started", marker.readText())
        } finally {
            marker.delete()
        }
    }

    @Test fun cancelAfterFileWritePreservesEffectAndStopsNativeThread() {
        val marker = File(context.cacheDir, "native-cancel-${UUID.randomUUID()}")
        try {
            val source =
                """
                native.files.writeText(${JsonPrimitive(marker.absolutePath)}, 'started');
                native.java.staticCall('java.lang.Thread','sleep',['long'],['30000']);
                native.files.writeText(${JsonPrimitive(marker.absolutePath)}, 'late');
                return 1;
                """.trimIndent()
            val result = client.execute(params(source), JsCancellation { marker.exists() })
            assertEquals(result.detail, JsExecutionStatus.INTERRUPTED, result.status)
            assertEquals("started", marker.readText())
            val next = client.execute(params("return 2;"))
            assertEquals(next.detail, JsExecutionStatus.SUCCESS, next.status)
            assertEquals("started", marker.readText())
        } finally {
            marker.delete()
        }
    }

    @Test fun revokingAndReenablingWhileRunningDoesNotRenewTheOldGrant() {
        val marker = File(context.cacheDir, "native-revoke-${UUID.randomUUID()}")
        val settings =
            com.helix.runtime.quickjs
                .JsNativeAccessSettings(context)
        var revoked = false
        try {
            val source =
                """
                native.files.writeText(${JsonPrimitive(marker.absolutePath)}, 'started');
                native.java.staticCall('java.lang.Thread','sleep',['long'],['30000']);
                native.files.writeText(${JsonPrimitive(marker.absolutePath)}, 'late');
                return 1;
                """.trimIndent()
            val result =
                client.execute(
                    params(source),
                    JsCancellation {
                        if (!revoked && marker.exists()) {
                            settings.setEnabled(false)
                            settings.setEnabled(true)
                            revoked = true
                        }
                        false
                    },
                )
            assertTrue(revoked)
            assertEquals(result.detail, JsExecutionStatus.INTERRUPTED, result.status)
            assertEquals("started", marker.readText())
            val fresh = client.execute(params("return 3;"))
            assertEquals(fresh.detail, JsExecutionStatus.SUCCESS, fresh.status)
            assertEquals("started", marker.readText())
        } finally {
            marker.delete()
        }
    }

    @Test fun nativeResultsAreBoundedAndServiceNotExported() {
        val file = File(context.cacheDir, "native-large-${UUID.randomUUID()}")
        try {
            file.writeBytes(ByteArray(256 * 1024 + 1))
            val source = "return native.files.readText(${JsonPrimitive(file.absolutePath)});"
            val result = client.execute(params(source))
            assertEquals(result.detail, JsExecutionStatus.JS_ERROR, result.status)
            val info =
                context.packageManager.getServiceInfo(
                    android.content.ComponentName(context, JsNativeExecutionService::class.java),
                    0,
                )
            assertFalse(info.exported)
        } finally {
            file.delete()
        }
    }
}
