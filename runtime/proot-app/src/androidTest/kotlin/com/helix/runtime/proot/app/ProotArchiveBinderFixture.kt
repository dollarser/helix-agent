package com.helix.runtime.proot.app

import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.helix.runtime.proot.ipc.ProotJobArchive
import com.helix.runtime.proot.ipc.ProotJobHandler
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobResultHandler
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.ProotRuntimeServiceBinder

@Suppress("DEPRECATION")
internal fun fetchArchiveThroughBinder(
    store: ProotJobStore,
    record: ProotJobRecord,
): ProotJobArchive {
    val handler =
        object : ProotJobHandler, ProotJobResultHandler {
            override fun submit(
                spec: ProotJobSpec,
                inputPfd: ParcelFileDescriptor,
                outputPfd: ParcelFileDescriptor,
            ) = error("Fetch must not submit")

            override fun query(jobId: String) = store.load(jobId)

            override fun cancel(jobId: String) = error("Fetch must not cancel")

            override fun reconcile(
                jobId: String,
                reconciledAtEpochMs: Long,
            ) = error("Fetch must not reconcile")

            override fun acknowledgeResult(
                jobId: String,
                terminalCommit: String,
                now: Long,
            ) = error("Fetch must not acknowledge")

            override fun fetchResult(jobId: String) = ProotResultArchiveStore(store).open(jobId)
        }
    val binder = ProotRuntimeServiceBinder({ byteArrayOf() }, { true }, jobHandler = handler)
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
        data.writeString(record.jobId)
        check(binder.transact(ProotRuntimeProtocol.TX_JOB_FETCH_RESULT, data, reply, 0))
        val (status, payload) = ProotJobWire.readJobReply(reply)
        check(status == ProotRuntimeProtocol.REPLY_JOB_STATE)
        val received = ProotJobRecordCodec.parse(requireNotNull(payload))
        val descriptor =
            requireNotNull(reply.readParcelable<ParcelFileDescriptor>(ProotJobRecord::class.java.classLoader))
        ProotJobArchive(received, descriptor)
    } finally {
        data.recycle()
        reply.recycle()
    }
}
