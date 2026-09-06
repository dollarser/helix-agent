package com.helix.runtime.cli.client

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import java.security.MessageDigest

class CliModelJobClient(private val supervisor: CliRuntimeSupervisor) {
    sealed interface StateOutcome {
        data class Ok(val record: CliModelJobRecord, val events: List<ModelEvent>? = null) : StateOutcome
        data object Unknown : StateOutcome
        data class Unavailable(val cause: CliRuntimeVerification.Cause) : StateOutcome
    }
    sealed interface AwaitOutcome {
        data class Terminal(val record: CliModelJobRecord, val events: List<ModelEvent>? = null) : AwaitOutcome
        data object TimedOut : AwaitOutcome
        data class Unavailable(val cause: CliRuntimeVerification.Cause) : AwaitOutcome
    }

    fun submitAndAwaitFixed(
        jobId: String,
        timeoutMs: Long = 120_000L,
        pollIntervalMs: Long = 100L,
    ): AwaitOutcome = submitAndAwait(
        jobId,
        ModelRequest("gpt-6-astra", listOf(ModelMessage(ModelRole.USER, "Reply exactly HELIX_OK"))),
        timeoutMs,
        pollIntervalMs,
    )

    fun submitAndAwait(
        jobId: String,
        request: ModelRequest,
        timeoutMs: Long = 120_000L,
        pollIntervalMs: Long = 100L,
        provider: CliModelProvider = CliModelProvider.CODEX,
    ): AwaitOutcome {
        val payload = CliModelRequestCodec.encode(request, provider)
        val requestSha256 = sha256(payload)
        val connection = supervisor.openConnection()
        if (connection is CliRuntimeConnection.Refused) return AwaitOutcome.Unavailable(connection.cause)
        connection as CliRuntimeConnection.Opened
        return try {
            val submitted = transactOn(
                connection.binder,
                CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT,
                jobId,
                requestSha256,
                payload,
            )
            if (submitted.status !in setOf(CliRuntimeProtocol.REPLY_JOB_ACCEPTED, CliRuntimeProtocol.REPLY_JOB_DUPLICATE)) {
                return AwaitOutcome.Unavailable(submitted.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            }
            submitted.record?.takeIf { it.state.terminal }?.let {
                return terminalFrom(transactOn(connection.binder, CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE, jobId, null, null))
            }
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start <= timeoutMs) {
                val queried = transactOn(connection.binder, CliRuntimeProtocol.TRANSACTION_JOB_QUERY, jobId, null, null)
                queried.record?.takeIf { it.state.terminal }?.let {
                    return terminalFrom(transactOn(connection.binder, CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE, jobId, null, null))
                }
                if (queried.status != CliRuntimeProtocol.REPLY_JOB_STATE) {
                    return AwaitOutcome.Unavailable(queried.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
                }
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AwaitOutcome.TimedOut
                }
            }
            transactOn(connection.binder, CliRuntimeProtocol.TRANSACTION_JOB_CANCEL, jobId, null, null)
            AwaitOutcome.TimedOut
        } finally {
            supervisor.closeConnection(connection)
        }
    }

    fun query(jobId: String): StateOutcome = state(CliRuntimeProtocol.TRANSACTION_JOB_QUERY, jobId)
    fun cancel(jobId: String): StateOutcome = state(CliRuntimeProtocol.TRANSACTION_JOB_CANCEL, jobId)
    fun reconcile(jobId: String): StateOutcome = state(CliRuntimeProtocol.TRANSACTION_JOB_RECONCILE, jobId)

    /** Debug-device seam: force Binder death so tests can prove query-only recovery. */
    fun debugKillRuntime() {
        val connection = supervisor.openConnection()
        if (connection !is CliRuntimeConnection.Opened) return
        val data = Parcel.obtain()
        try {
            runCatching { connection.binder.transact(CliRuntimeProtocol.TRANSACTION_DEBUG_SELF_KILL, data, null, 0) }
        } finally {
            data.recycle()
            supervisor.closeConnection(connection)
        }
    }

    private fun state(code: Int, jobId: String): StateOutcome {
        val result = transact(code, jobId, null, null)
        return when (result.status) {
            CliRuntimeProtocol.REPLY_JOB_STATE -> StateOutcome.Ok(result.record!!, result.events)
            CliRuntimeProtocol.REPLY_JOB_NOT_FOUND -> StateOutcome.Unknown
            else -> StateOutcome.Unavailable(result.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
        }
    }

    private data class WireResult(
        val status: Int = CliRuntimeProtocol.REPLY_JOB_INVALID,
        val record: CliModelJobRecord? = null,
        val events: List<ModelEvent>? = null,
        val cause: CliRuntimeVerification.Cause? = null,
    )

    private fun transact(code: Int, jobId: String, requestSha256: String?, payload: ByteArray?): WireResult {
        val connection = supervisor.openConnection()
        if (connection is CliRuntimeConnection.Refused) return WireResult(cause = connection.cause)
        connection as CliRuntimeConnection.Opened
        return try {
            transactOn(connection.binder, code, jobId, requestSha256, payload)
        } finally {
            supervisor.closeConnection(connection)
        }
    }

    private fun transactOn(
        binder: IBinder,
        code: Int,
        jobId: String,
        requestSha256: String?,
        payload: ByteArray?,
    ): WireResult {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            var readEnd: ParcelFileDescriptor? = null
            return try {
                data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
                data.writeString(jobId)
                requestSha256?.let(data::writeString)
                if (payload != null) {
                    val pipe = ParcelFileDescriptor.createPipe()
                    readEnd = pipe[0]
                    val writeEnd = pipe[1]
                    Thread({ CliPfdChannel.write(writeEnd, payload, CliModelRequestCodec.MAX_BYTES) }, "cli-request-$jobId").start()
                    data.writeParcelable(readEnd, 0)
                }
                if (!binder.transact(code, data, reply, 0)) return WireResult()
                val status = reply.readInt()
                val record = if (status in RECORD_REPLIES) {
                    reply.readString()?.let { CliModelJobRecordCodec.decode(it) }
                } else null
                val eventPayload = if (record != null && reply.readInt() == 1) {
                    val output = reply.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)
                        ?: return WireResult()
                    CliPfdChannel.read(output, CliModelEventCodec.MAX_BYTES)
                } else null
                val events = eventPayload?.let {
                    require(record?.outputSha256 == sha256(it))
                    CliModelEventCodec.decode(it)
                }
                if (status in RECORD_REPLIES && record == null) WireResult() else WireResult(status, record, events)
            } catch (_: DeadObjectException) {
                WireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            } catch (_: RuntimeException) {
                WireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            } finally {
                readEnd?.close()
                reply.recycle()
                data.recycle()
            }
    }

    private fun terminalFrom(result: WireResult): AwaitOutcome =
        if (result.status == CliRuntimeProtocol.REPLY_JOB_STATE && result.record != null) {
            AwaitOutcome.Terminal(result.record, result.events)
        } else {
            AwaitOutcome.Unavailable(result.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val RECORD_REPLIES = setOf(
            CliRuntimeProtocol.REPLY_JOB_ACCEPTED,
            CliRuntimeProtocol.REPLY_JOB_DUPLICATE,
            CliRuntimeProtocol.REPLY_JOB_STATE,
        )
    }
}
