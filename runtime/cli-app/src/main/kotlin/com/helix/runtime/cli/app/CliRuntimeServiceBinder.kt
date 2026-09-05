package com.helix.runtime.cli.app

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.helix.runtime.cli.client.CliModelRequestCodec
import com.helix.runtime.cli.client.CliPfdChannel
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliRuntimeProtocol

internal class CliRuntimeServiceBinder(
    private val statusProvider: () -> String,
    private val callerVerifier: (Int) -> Boolean,
    private val jobRunner: CodexPayloadJobRunner? = null,
) : Binder() {
    @Suppress("ReturnCount") // Unknown transaction, rejected caller, and success are distinct outcomes.
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (BuildConfig.DEBUG && code == CliRuntimeProtocol.TRANSACTION_DEBUG_SELF_KILL && callerVerifier(getCallingUid())) {
            android.os.Process.killProcess(android.os.Process.myPid())
            return true
        }
        if (reply == null || code !in TRANSACTIONS) return super.onTransact(code, data, reply, flags)
        if (!callerVerifier(getCallingUid())) {
            reply.writeInt(CliRuntimeProtocol.REPLY_CALLER_MISMATCH)
            return true
        }
        return runCatching {
            data.enforceInterface(CliRuntimeProtocol.DESCRIPTOR)
            if (code == CliRuntimeProtocol.TRANSACTION_STATUS) writeStatus(reply) else writeJob(code, data, reply)
            true
        }.getOrElse {
            reply.setDataPosition(0)
            reply.setDataSize(0)
            reply.writeInt(CliRuntimeProtocol.REPLY_JOB_INVALID)
            true
        }
    }

    private fun writeStatus(reply: Parcel) {
        val status = statusProvider()
        require(
            status.encodeToByteArray().size <= CliRuntimeProtocol.MAX_STATUS_BYTES,
        ) { "CLI status exceeds wire limit" }
        reply.writeInt(CliRuntimeProtocol.REPLY_OK)
        reply.writeString(status)
    }

    private fun writeJob(code: Int, data: Parcel, reply: Parcel) {
        val runner = jobRunner ?: run { reply.writeInt(CliRuntimeProtocol.REPLY_JOB_INVALID); return }
        val jobId = data.readString() ?: error("missing jobId")
        com.helix.runtime.cli.client.CliModelJobRecord.checkJobId(jobId)
        when (code) {
            CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT -> {
                val hash = data.readString() ?: error("missing request hash")
                val input = data.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)
                    ?: error("missing request PFD")
                val payload = CliPfdChannel.read(input, CliModelRequestCodec.MAX_BYTES)
                when (val result = runner.submit(jobId, hash, payload)) {
                    is CodexPayloadSubmit.Accepted -> writeRecord(reply, CliRuntimeProtocol.REPLY_JOB_ACCEPTED, result.record)
                    is CodexPayloadSubmit.Duplicate -> writeRecord(reply, CliRuntimeProtocol.REPLY_JOB_DUPLICATE, result.record)
                    CodexPayloadSubmit.RequestMismatch -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_REQUEST_MISMATCH)
                    CodexPayloadSubmit.Busy -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_BUSY)
                    CodexPayloadSubmit.JournalFull -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_JOURNAL_FULL)
                }
            }
            CliRuntimeProtocol.TRANSACTION_JOB_QUERY -> writeRecordOrMissing(reply, runner.query(jobId))
            CliRuntimeProtocol.TRANSACTION_JOB_CANCEL -> writeRecordOrMissing(reply, runner.cancel(jobId))
            CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE -> writeReconcile(reply, runner, jobId)
            else -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_INVALID)
        }
    }

    private fun writeRecordOrMissing(reply: Parcel, record: CodexModelJobRecord?) {
        if (record == null) reply.writeInt(CliRuntimeProtocol.REPLY_JOB_NOT_FOUND)
        else writeRecord(reply, CliRuntimeProtocol.REPLY_JOB_STATE, record)
    }

    private fun writeRecord(reply: Parcel, status: Int, record: CodexModelJobRecord) {
        reply.writeInt(status)
        reply.writeString(CliModelJobRecordCodec.encode(record))
        reply.writeInt(0)
    }

    private fun writeReconcile(reply: Parcel, runner: CodexPayloadJobRunner, jobId: String) {
        val prepared = runner.prepareReconcile(jobId)
        if (prepared == null) { reply.writeInt(CliRuntimeProtocol.REPLY_JOB_NOT_FOUND); return }
        val payload = prepared.payload
        if (payload == null) {
            val record = if (prepared.record.state.terminal && prepared.record.reconciledAtEpochMillis == null) {
                runner.finishReconcile(prepared.record)
            } else prepared.record
            writeRecord(reply, CliRuntimeProtocol.REPLY_JOB_STATE, record)
            return
        }
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        reply.writeInt(CliRuntimeProtocol.REPLY_JOB_STATE)
        reply.writeString(CliModelJobRecordCodec.encode(prepared.record))
        reply.writeInt(1)
        reply.writeParcelable(readEnd, 0)
        readEnd.close()
        Thread({
            runCatching { CliPfdChannel.write(writeEnd, payload, com.helix.runtime.cli.client.CliModelEventCodec.MAX_BYTES) }
                .onSuccess { runner.finishReconcile(prepared.record) }
                .onFailure { writeEnd.close() }
        }, "cli-result-${prepared.record.jobId}").start()
    }

    private companion object {
        val TRANSACTIONS = setOf(
            CliRuntimeProtocol.TRANSACTION_STATUS,
            CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT,
            CliRuntimeProtocol.TRANSACTION_JOB_QUERY,
            CliRuntimeProtocol.TRANSACTION_JOB_CANCEL,
            CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE,
        )
    }
}
