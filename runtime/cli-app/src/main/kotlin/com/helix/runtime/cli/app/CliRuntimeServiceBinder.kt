package com.helix.runtime.cli.app

import android.os.Binder
import android.os.Parcel
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliRuntimeProtocol

internal class CliRuntimeServiceBinder(
    private val statusProvider: () -> String,
    private val callerVerifier: (Int) -> Boolean,
    private val jobRunner: CodexModelJobRunner? = null,
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
                require(hash == CliRuntimeProtocol.FIXED_CODEX_SMOKE_SHA256 || runner.query(jobId) != null)
                when (val result = runner.submit(jobId, hash)) {
                    is CodexModelJobSubmit.Accepted -> writeRecord(reply, CliRuntimeProtocol.REPLY_JOB_ACCEPTED, result.record)
                    is CodexModelJobSubmit.Duplicate -> writeRecord(reply, CliRuntimeProtocol.REPLY_JOB_DUPLICATE, result.record)
                    CodexModelJobSubmit.RequestMismatch -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_REQUEST_MISMATCH)
                    CodexModelJobSubmit.Busy -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_BUSY)
                    CodexModelJobSubmit.JournalFull -> reply.writeInt(CliRuntimeProtocol.REPLY_JOB_JOURNAL_FULL)
                }
            }
            CliRuntimeProtocol.TRANSACTION_JOB_QUERY -> writeRecordOrMissing(reply, runner.query(jobId))
            CliRuntimeProtocol.TRANSACTION_JOB_CANCEL -> writeRecordOrMissing(reply, runner.cancel(jobId))
            CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE -> writeRecordOrMissing(reply, runner.reconcile(jobId))
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
