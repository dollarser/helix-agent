package com.helix.app.provider

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliStatusHandshakeClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliStatusHandshakeDeviceTest {
    @Test
    fun deadBinderProducesFailedOutcome() {
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean = throw DeadObjectException()
            }
        assertEquals(CliStatusHandshakeClient.Outcome.Failed, CliStatusHandshakeClient.transact(binder))
    }

    @Test
    fun unhandledTransactionProducesFailedOutcome() {
        assertEquals(CliStatusHandshakeClient.Outcome.Failed, CliStatusHandshakeClient.transact(Binder()))
    }

    @Test
    fun missingDocumentProducesFailedOutcome() {
        assertEquals(CliStatusHandshakeClient.Outcome.Failed, transactReply(CliRuntimeProtocol.REPLY_OK))
    }

    @Test
    fun callerMismatchKeepsItsDistinctOutcome() {
        assertEquals(
            CliStatusHandshakeClient.Outcome.CallerMismatch,
            transactReply(CliRuntimeProtocol.REPLY_CALLER_MISMATCH),
        )
    }

    private fun transactReply(status: Int): CliStatusHandshakeClient.Outcome {
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    data.enforceInterface(CliRuntimeProtocol.DESCRIPTOR)
                    requireNotNull(reply).writeInt(status)
                    if (status == CliRuntimeProtocol.REPLY_OK) reply.writeString(null)
                    return true
                }
            }
        return CliStatusHandshakeClient.transact(binder)
    }
}
