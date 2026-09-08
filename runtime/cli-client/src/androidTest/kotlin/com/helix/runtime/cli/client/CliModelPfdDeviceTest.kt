package com.helix.runtime.cli.client

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliModelPfdDeviceTest {
    private val events = listOf(ModelEvent.TextDelta("fixture"), ModelEvent.Completed("stop"))
    private val payload = CliModelEventCodec.encode(events)

    @Test
    fun validPfdResultRetainsVerifiedEvents() {
        val result = receive(unreadable = false, hash = cliPayloadSha256(payload))
        assertEquals(CliRuntimeProtocol.REPLY_JOB_STATE, result.status)
        assertEquals(events, result.events)
    }

    @Test
    fun mismatchedHashDoesNotReturnEvents() {
        val result = receive(unreadable = false, hash = "0".repeat(64))
        assertEquals(CliRuntimeVerification.Cause.HANDSHAKE_FAILED, result.cause)
        assertEquals(null, result.events)
    }

    @Test
    fun unreadableDescriptorReturnsFailureWithoutReplay() {
        val result = receive(unreadable = true, hash = cliPayloadSha256(payload))
        assertEquals(CliRuntimeVerification.Cause.HANDSHAKE_FAILED, result.cause)
        assertEquals(null, result.events)
    }

    private fun receive(
        unreadable: Boolean,
        hash: String,
    ): CliModelWireResult {
        val pipe = ParcelFileDescriptor.createPipe()
        var calls = 0
        try {
            if (!unreadable) CliPfdChannel.write(pipe[1], payload, CliModelEventCodec.MAX_BYTES)
            val binder =
                object : Binder() {
                    override fun onTransact(
                        code: Int,
                        data: Parcel,
                        reply: Parcel?,
                        flags: Int,
                    ): Boolean {
                        calls++
                        val record =
                            CliModelJobRecord(
                                "job_000000000001",
                                "a".repeat(64),
                                CliModelJobState.SUCCEEDED,
                                1L,
                                2L,
                                "fixture-model",
                                hash,
                            )
                        requireNotNull(reply).writeInt(CliRuntimeProtocol.REPLY_JOB_STATE)
                        reply.writeString(CliModelJobRecordCodec.encode(record))
                        reply.writeInt(1)
                        reply.writeParcelable(pipe[if (unreadable) 1 else 0], 0)
                        return true
                    }
                }
            return CliModelJobWire.transact(
                binder,
                CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE,
                "job_000000000001",
                null,
                null,
            )
        } finally {
            pipe.forEach { it.close() }
            assertEquals(1, calls)
        }
    }
}
