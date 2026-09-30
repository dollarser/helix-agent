package com.helix.app.proot

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.helix.runtime.proot.client.PtySessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fake binding callbacks on Android; never starts a shell or Runtime. */
class PtyConnectionFailureDeviceTest {
    private class BindingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var bound = 0
        var unbound = 0
        var accept = true
        lateinit var callback: ServiceConnection
        private val component get() = ComponentName(packageName, "fixture")

        override fun getApplicationContext(): Context = this

        override fun bindService(
            intent: Intent,
            connection: ServiceConnection,
            flags: Int,
        ): Boolean {
            bound++
            callback = connection
            if (accept) connection.onServiceConnected(component, Binder())
            return accept
        }

        override fun unbindService(connection: ServiceConnection) {
            unbound++
        }

        fun lateConnection() {
            callback.onServiceConnected(component, Binder())
        }
    }

    @Test fun refusedBindingIsReleasedAndCannotBeReused() {
        val context = BindingContext().apply { accept = false }
        val client = PtySessionClient(context)
        assertThrows(IllegalStateException::class.java) { client.connect() }
        assertEquals(1, context.unbound)
        context.lateConnection()
        assertThrows(IllegalStateException::class.java) { client.connect() }
        client.close()
        assertEquals(1, context.bound)
        assertEquals(1, context.unbound)
    }

    @Test fun duplicateConnectDoesNotTearDownTheCurrentConnection() {
        val context = BindingContext()
        val client = PtySessionClient(context)
        client.connect()
        try {
            assertThrows(IllegalStateException::class.java) { client.connect() }
            assertEquals(0, context.unbound)
        } finally {
            client.close()
        }
        context.lateConnection()
        assertThrows(IllegalStateException::class.java) { client.connect() }
        assertEquals(1, context.unbound)
    }
}
