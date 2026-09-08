package com.helix.runtime.cli.client

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliModelJobWireDeviceTest {
    @Test
    fun binderDeathIsUnavailableWithoutReplay() = verifyFailure(DeadObjectException())

    @Test
    fun remoteFailureIsUnavailableWithoutReplay() = verifyFailure(RemoteException("fixture failure"))

    @Test
    fun missingStateDocumentIsNotSuccessful() {
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    requireNotNull(reply).writeInt(CliRuntimeProtocol.REPLY_JOB_STATE)
                    reply.writeString(null)
                    return true
                }
            }
        assertEquals(CliRuntimeProtocol.REPLY_JOB_INVALID, query(binder).status)
    }

    private fun verifyFailure(failure: RemoteException) {
        var calls = 0
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    calls++
                    throw failure
                }
            }
        assertEquals(CliRuntimeVerification.Cause.HANDSHAKE_FAILED, query(binder).cause)
        assertEquals(1, calls)
    }

    private fun query(binder: Binder) =
        CliModelJobWire.transact(binder, CliRuntimeProtocol.TRANSACTION_JOB_QUERY, "fixture-job", null, null)
}
