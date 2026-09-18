package com.helix.runtime.proot.ipc

import android.os.IBinder
import android.os.Parcel

/** Host-authored identity, not an authorization grant. All fields must match on later operations. */
data class DetachedJobBinding(
    val sessionId: String,
    val turnId: String,
    val toolCallId: String,
    val jobId: String,
    val executionId: String,
    val inputManifestSha256: String,
) {
    init {
        listOf(sessionId, turnId, toolCallId).forEach {
            require(it.length in 1..128 && it.none(Char::isISOControl))
        }
        ProotJobRecordCodec.checkJobId(jobId)
        ProotJobRecordCodec.checkExecutionId(executionId)
        require(inputManifestSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun matches(spec: ProotJobSpec): Boolean =
        jobId == spec.jobId && executionId == spec.executionId && inputManifestSha256 == spec.inputManifestSha256
}

/** Separate protocol: synchronous v2 submission never acquires a detached owner. */
object DetachedJobProtocol {
    const val DESCRIPTOR = "com.helix.runtime.proot.DetachedJob.v1"
    const val SERVICE = "com.helix.runtime.proot.app.ProotDetachedJobService"
    const val SUBMIT = IBinder.FIRST_CALL_TRANSACTION
    const val QUERY = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CANCEL = IBinder.FIRST_CALL_TRANSACTION + 2

    fun writeBinding(
        parcel: Parcel,
        binding: DetachedJobBinding,
    ) {
        with(binding) {
            listOf(sessionId, turnId, toolCallId, jobId, executionId, inputManifestSha256).forEach(parcel::writeString)
        }
    }

    fun readBinding(parcel: Parcel): DetachedJobBinding =
        DetachedJobBinding(
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
        )
}
