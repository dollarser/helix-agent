package com.helix.runtime.quickjs

import android.os.Parcel
import android.os.Parcelable

/**
 * HXA-051 execution request (architecture doc local-code-execution §3.1 request type).
 *
 * `sourceUtf8`/`inputJsonUtf8` carry the INLINE (≤ [JsProtocol.PARCEL_INLINE_MAX_BYTES])
 * payload; larger payloads travel through read-only `ParcelFileDescriptor`s handed over
 * in the EXECUTE transaction envelope ([JsExecutionWire.ExecuteEnvelope]) instead. An
 * empty `inputJsonUtf8` means "no input"; source is expected non-empty by the caller, and
 * the service evaluates an empty source to `undefined` (result `null`).
 *
 * [deadlineNanos] is the client's monotonic deadline (`send time + limits.timeoutMs`).
 * `System.nanoTime()` (CLOCK_MONOTONIC) is comparable across processes, so the service's
 * [app.cash.zipline.InterruptHandler] and the client watchdog key off the same instant
 * (doc 03 §4.3/§4.5: deadline checks use the monotonic clock).
 */
class JsExecutionRequest(
    val executionId: String,
    val sourceUtf8: ByteArray,
    val inputJsonUtf8: ByteArray,
    val limits: JsExecutionLimits,
    val deadlineNanos: Long,
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(executionId)
        dest.writeByteArray(sourceUtf8)
        dest.writeByteArray(inputJsonUtf8)
        dest.writeParcelable(limits, flags)
        dest.writeLong(deadlineNanos)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<JsExecutionRequest> =
            object : Parcelable.Creator<JsExecutionRequest> {
                override fun createFromParcel(source: Parcel): JsExecutionRequest {
                    val executionId = source.readString() ?: ""
                    val sourceUtf8 = source.createByteArray() ?: ByteArray(0)
                    val inputJsonUtf8 = source.createByteArray() ?: ByteArray(0)
                    val limits =
                        source.readParcelable(JsExecutionLimits::class.java.classLoader) as? JsExecutionLimits
                            ?: throw IllegalArgumentException("malformed JsExecutionRequest: missing limits")
                    val deadlineNanos = source.readLong()
                    return JsExecutionRequest(executionId, sourceUtf8, inputJsonUtf8, limits, deadlineNanos)
                }

                override fun newArray(size: Int): Array<JsExecutionRequest?> = arrayOfNulls(size)

                fun newInstance(size: IntArray): Array<JsExecutionRequest?> = arrayOfNulls(size.size)
            }
    }
}
