package com.helix.runtime.quickjs

import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Actual Parcel/PFD ownership, no model, native script, network or Runtime service is invoked. */
class JsEnvelopeOwnershipDeviceTest {
    private fun request() =
        JsExecutionRequest(
            "descriptor-fixture",
            byteArrayOf(),
            byteArrayOf(),
            JsExecutionLimits.DEFAULTS,
            System.nanoTime() + 5_000_000_000L,
        )

    @Test fun malformedEnvelopeClosesAlreadyDecodedDescriptors() {
        val pipe = ParcelFileDescriptor.createPipe()
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(JsProtocol.PROTOCOL_VERSION)
            parcel.writeParcelable(request(), 0)
            parcel.writeByte(1)
            parcel.writeFileDescriptor(pipe[0].fileDescriptor)
            parcel.writeByte(2) // invalid second presence flag, after first descriptor has been duplicated
            parcel.setDataPosition(0)
            assertThrows(JsExecutionWire.ProtocolException::class.java) { JsExecutionWire.readExecute(parcel) }
        } finally {
            parcel.recycle()
            pipe[0].close()
        }
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { writer ->
            assertThrows(java.io.IOException::class.java) { writer.write(1) }
        }
    }

    @Test fun rejectedEnvelopeUseClosesAllOwnedHandles() {
        val source = ParcelFileDescriptor.createPipe()
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        try {
            val envelope = JsExecutionWire.ExecuteEnvelope(source[0], input[0], output[1], 0, 0, 0, 0)
            assertThrows(IllegalStateException::class.java) { envelope.use { error("rejected before execution") } }
            assertFalse(source[0].fileDescriptor.valid())
            assertFalse(input[0].fileDescriptor.valid())
            assertFalse(output[1].fileDescriptor.valid())
        } finally {
            (source + input + output).forEach { runCatching { it.close() } }
        }
    }
}
