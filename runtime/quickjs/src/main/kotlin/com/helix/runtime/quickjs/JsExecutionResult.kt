package com.helix.runtime.quickjs

import android.os.Parcel
import android.os.Parcelable

/**
 * HXA-051 execution result — the single closed result type for the whole protocol
 * (roadmap M5 / doc 03 §4).
 *
 * On the wire the service fills [servicePid]/[serviceUid] with its own (isolated)
 * process identity — the isolation observation point the client asserts without ever
 * trusting a process-name string. [outputUtf8] on the wire holds the output only when it
 * fits the inline parcel cap; when the caller provided an output PFD the full bytes were
 * written to that file and the client re-materializes them into this same class, so the
 * caller always sees [outputUtf8] complete on [JsExecutionStatus.SUCCESS].
 *
 * Client-side-only outcomes ([JsExecutionStatus.BIND_FAILED], [JsExecutionStatus.UNKNOWN],
 * [JsExecutionStatus.CANCELLED] before start) are built with [clientFailure], which marks
 * the service identity as absent (-1).
 */
class JsExecutionResult(
    val executionId: String,
    val status: JsExecutionStatus,
    val outputUtf8: ByteArray,
    val outputBytes: Long,
    val outputSha256Hex: String,
    val inputSha256Hex: String,
    val detail: String,
    val servicePid: Int,
    val serviceUid: Int,
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(executionId)
        dest.writeString(status.name)
        dest.writeByteArray(outputUtf8)
        dest.writeLong(outputBytes)
        dest.writeString(outputSha256Hex)
        dest.writeString(inputSha256Hex)
        dest.writeString(detail)
        dest.writeInt(servicePid)
        dest.writeInt(serviceUid)
    }

    companion object {
        /** Client-side failure with no service identity (bind/cancel/protocol anomalies). */
        fun clientFailure(
            executionId: String,
            status: JsExecutionStatus,
            detail: String,
            inputSha256Hex: String,
        ): JsExecutionResult =
            JsExecutionResult(
                executionId = executionId,
                status = status,
                outputUtf8 = ByteArray(0),
                outputBytes = 0L,
                outputSha256Hex = "",
                inputSha256Hex = inputSha256Hex,
                detail = detail.take(JsProtocol.MAX_DETAIL_CHARS),
                servicePid = -1,
                serviceUid = -1,
            )

        /** Service-side failure that still carries the service's own (isolated) identity. */
        fun serviceFailure(
            executionId: String,
            status: JsExecutionStatus,
            detail: String,
            inputSha256Hex: String,
            servicePid: Int,
            serviceUid: Int,
        ): JsExecutionResult =
            JsExecutionResult(
                executionId = executionId,
                status = status,
                outputUtf8 = ByteArray(0),
                outputBytes = 0L,
                outputSha256Hex = "",
                inputSha256Hex = inputSha256Hex,
                detail = detail.take(JsProtocol.MAX_DETAIL_CHARS),
                servicePid = servicePid,
                serviceUid = serviceUid,
            )

        @JvmField
        val CREATOR: Parcelable.Creator<JsExecutionResult> =
            object : Parcelable.Creator<JsExecutionResult> {
                override fun createFromParcel(source: Parcel): JsExecutionResult =
                    JsExecutionResult(
                        executionId = source.readString() ?: "",
                        status = JsExecutionStatus.fromWire(source.readString()),
                        outputUtf8 = source.createByteArray() ?: ByteArray(0),
                        outputBytes = source.readLong(),
                        outputSha256Hex = source.readString() ?: "",
                        inputSha256Hex = source.readString() ?: "",
                        detail = source.readString() ?: "",
                        servicePid = source.readInt(),
                        serviceUid = source.readInt(),
                    )

                override fun newArray(size: Int): Array<JsExecutionResult?> = arrayOfNulls(size)

                fun newInstance(size: IntArray): Array<JsExecutionResult?> = arrayOfNulls(size.size)
            }
    }
}
