package com.helix.app.localmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Compiled by host gate; requires explicit device authorization to execute. No model download. */
class LocalModelRuntimeDeviceTest {
    @Test
    fun privateServiceRejectsBadAssetsAndHasObservableDeath() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val component = ComponentName(context, LocalModelRuntimeService::class.java)

            @Suppress("DEPRECATION")
            val info = context.packageManager.getServiceInfo(component, 0)
            assertFalse(info.exported)
            assertEquals("${context.packageName}:model_runtime", info.processName)
            val connected = CompletableDeferred<IBinder>()
            val death = CompletableDeferred<Unit>()
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        binder: IBinder,
                    ) {
                        connected.complete(binder)
                    }

                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
            assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
            val file = File.createTempFile("bad-model-", ".gguf", context.cacheDir)
            try {
                val binder = withTimeout(10000) { connected.await() }
                binder.linkToDeath({ death.complete(Unit) }, 0)
                val runtime = ILocalModelRuntime.Stub.asInterface(binder)
                file.writeBytes(byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0))
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    assertThrows(IllegalArgumentException::class.java) { runtime.load(it, 8, "a".repeat(64), 2048, 2) }
                }
                val hash =
                    MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    assertEquals(-1, runtime.load(it, 8, hash, 2048, 2))
                }
                assertTrue(binder.isBinderAlive)
                runtime.shutdown()
                withTimeout(10000) { death.await() }
                assertFalse(binder.isBinderAlive)
            } finally {
                context.unbindService(connection)
                file.delete()
            }
        }
}
