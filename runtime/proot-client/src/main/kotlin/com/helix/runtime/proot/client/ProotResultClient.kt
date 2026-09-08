package com.helix.runtime.proot.client

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.helix.runtime.proot.ipc.ProotJobArchive
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotJobWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol

/** Explicit original-result retrieval. Caller must verify the archive before durable import or acknowledgement. */
class ProotResultClient(
    private val supervisor: ProotRuntimeSupervisor,
) {
    fun fetch(expected: ProotJobRecord): ProotJobArchive? {
        require(expected.state == ProotJobState.SUCCEEDED && !expected.evidenceExpired)
        val connection = supervisor.openConnection()
        if (connection !is ProotConnection.Opened) return null
        return try {
            transact(connection.binder, expected)
        } finally {
            supervisor.closeConnection()
        }
    }

    /** Only call after verified durable import. No compatibility fallback to legacy reconcile. */
    fun acknowledge(expected: ProotJobRecord): ProotJobRecord? {
        require(expected.state.isTerminal && !expected.evidenceExpired)
        val connection = supervisor.openConnection()
        if (connection !is ProotConnection.Opened) return null
        return try {
            acknowledgeTransaction(connection.binder, expected)
        } finally {
            supervisor.closeConnection()
        }
    }

    private fun acknowledgeTransaction(
        binder: IBinder,
        expected: ProotJobRecord,
    ): ProotJobRecord? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
            data.writeString(expected.jobId)
            data.writeString(requireNotNull(expected.terminalCommit))
            if (!binder.transact(ProotRuntimeProtocol.TX_JOB_ACK_RESULT, data, reply, 0)) return null
            val (status, payload) = ProotJobWire.readJobReply(reply)
            if (status == ProotRuntimeProtocol.REPLY_JOB_STATE) {
                val record = ProotJobRecordCodec.parse(requireNotNull(payload))
                require(record.terminalCommit == expected.terminalCommit && record.reconciledAtEpochMs != null)
                record
            } else {
                null
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transact(
        binder: IBinder,
        expected: ProotJobRecord,
    ): ProotJobArchive? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
            data.writeString(expected.jobId)
            if (binder.transact(ProotRuntimeProtocol.TX_JOB_FETCH_RESULT, data, reply, 0)) {
                decode(reply, expected)
            } else {
                null
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(
        reply: Parcel,
        expected: ProotJobRecord,
    ): ProotJobArchive? {
        val (status, payload) = ProotJobWire.readJobReply(reply)
        if (status != ProotRuntimeProtocol.REPLY_JOB_STATE) return null
        val descriptor = requireNotNull(reply.readParcelable<ParcelFileDescriptor>(javaClass.classLoader))
        var delivered = false
        return try {
            val record = ProotJobRecordCodec.parse(requireNotNull(payload))
            require(record == expected && record.reconciledAtEpochMs == null)
            require(descriptor.statSize in 1..ProotRuntimeProtocol.MAX_RESULT_ARCHIVE_BYTES)
            ProotJobArchive(record, descriptor).also { delivered = true }
        } finally {
            if (!delivered) descriptor.close()
        }
    }
}
