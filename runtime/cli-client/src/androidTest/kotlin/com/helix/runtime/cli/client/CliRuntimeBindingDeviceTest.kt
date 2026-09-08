package com.helix.runtime.cli.client

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliRuntimeBindingDeviceTest {
    @Test
    fun refusalDoesNotUnbindAnUnboundConnection() {
        verifyRefused(CliRuntimeVerification.Cause.BIND_REFUSED) { false }
    }

    @Test
    fun securityFailureRetainsSignatureClassification() {
        verifyRefused(CliRuntimeVerification.Cause.SIGNATURE_MISMATCH) { throw SecurityException("fixture") }
    }

    @Test
    fun platformRuntimeFailureRemainsBindRefused() {
        verifyRefused(CliRuntimeVerification.Cause.BIND_REFUSED) { throw IllegalStateException("fixture") }
    }

    @Test
    fun nullBindingIsUnboundAndReportedAsHandshakeFailure() {
        verifyRefused(CliRuntimeVerification.Cause.HANDSHAKE_FAILED, unbinds = 1) { connection ->
            connection.onNullBinding(ComponentName("fixture", "service"))
            true
        }
    }

    @Test
    fun interruptionPreservesFlagAndReleasesBoundConnection() {
        Thread.currentThread().interrupt()
        try {
            verifyRefused(CliRuntimeVerification.Cause.TIMEOUT, unbinds = 1) { true }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun openedConnectionRemainsOwnedUntilExplicitClose() {
        val context =
            BindingContext { connection ->
                connection.onServiceConnected(ComponentName("fixture", "service"), Binder())
                true
            }
        val supervisor = CliRuntimeSupervisor(context)
        val opened = supervisor.openConnection()
        assertTrue(opened is CliRuntimeConnection.Opened)
        assertEquals(0, context.unbinds)
        supervisor.closeConnection(opened as CliRuntimeConnection.Opened)
        assertEquals(1, context.unbinds)
    }

    private fun verifyRefused(
        cause: CliRuntimeVerification.Cause,
        unbinds: Int = 0,
        action: (ServiceConnection) -> Boolean,
    ) {
        val context = BindingContext(action)
        assertEquals(CliRuntimeConnection.Refused(cause), CliRuntimeSupervisor(context).openConnection())
        assertEquals(1, context.binds)
        assertEquals(unbinds, context.unbinds)
    }

    private class BindingContext(
        private val action: (ServiceConnection) -> Boolean,
    ) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var binds = 0
        var unbinds = 0

        override fun getApplicationContext(): Context = this

        override fun bindService(
            service: Intent,
            conn: ServiceConnection,
            flags: Int,
        ): Boolean {
            binds++
            return action(conn)
        }

        override fun unbindService(conn: ServiceConnection) {
            unbinds++
        }
    }
}
