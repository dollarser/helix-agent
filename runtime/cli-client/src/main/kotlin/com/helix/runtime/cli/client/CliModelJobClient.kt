package com.helix.runtime.cli.client

import android.os.Parcel
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.runtime.cli.client.CliModelJobWire.transact as transactOn

class CliModelJobClient(
    private val supervisor: CliRuntimeSupervisor,
) {
    sealed interface StateOutcome {
        data class Ok(
            val record: CliModelJobRecord,
            val events: List<ModelEvent>? = null,
        ) : StateOutcome

        data object Unknown : StateOutcome

        data class Unavailable(
            val cause: CliRuntimeVerification.Cause,
        ) : StateOutcome
    }

    sealed interface AwaitOutcome {
        data class Terminal(
            val record: CliModelJobRecord,
            val events: List<ModelEvent>? = null,
        ) : AwaitOutcome

        data object TimedOut : AwaitOutcome

        data class Unavailable(
            val cause: CliRuntimeVerification.Cause,
        ) : AwaitOutcome
    }

    fun submitAndAwaitFixed(
        jobId: String,
        timeoutMs: Long = 120_000L,
        pollIntervalMs: Long = 100L,
    ): AwaitOutcome =
        submitAndAwait(
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
        val requestSha256 = cliPayloadSha256(payload)
        val connection = supervisor.openConnection()
        if (connection is CliRuntimeConnection.Refused) return AwaitOutcome.Unavailable(connection.cause)
        connection as CliRuntimeConnection.Opened
        return try {
            val submitted =
                transactOn(
                    connection.binder,
                    CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT,
                    jobId,
                    requestSha256,
                    payload,
                )
            CliModelJobAwaiter { code ->
                transactOn(connection.binder, code, jobId, null, null)
            }.await(submitted, timeoutMs, pollIntervalMs)
        } finally {
            supervisor.closeConnection(connection)
        }
    }

    fun query(jobId: String): StateOutcome = state(CliRuntimeProtocol.TRANSACTION_JOB_QUERY, jobId)

    fun cancel(jobId: String): StateOutcome = state(CliRuntimeProtocol.TRANSACTION_JOB_CANCEL, jobId)

    /** Reads verified result bytes without acknowledging or deleting Runtime evidence. */
    fun fetchResult(jobId: String): StateOutcome = state(CliRuntimeProtocol.TRANSACTION_JOB_FETCH_RESULT, jobId)

    /** Caller must persist or explicitly discard the result before acknowledging this exact identity. */
    fun acknowledgeResult(record: CliModelJobRecord): StateOutcome {
        val identity = record.requestSha256 + ":" + record.outputSha256.orEmpty()
        val result = transact(CliRuntimeProtocol.TRANSACTION_JOB_ACK_RESULT, record.jobId, identity, null)
        return if (result.status == CliRuntimeProtocol.REPLY_JOB_STATE && result.record != null) {
            StateOutcome.Ok(result.record)
        } else {
            StateOutcome.Unavailable(result.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
        }
    }

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

    private fun state(
        code: Int,
        jobId: String,
    ): StateOutcome {
        val result = transact(code, jobId, null, null)
        return when (result.status) {
            CliRuntimeProtocol.REPLY_JOB_STATE -> StateOutcome.Ok(result.record!!, result.events)
            CliRuntimeProtocol.REPLY_JOB_NOT_FOUND -> StateOutcome.Unknown
            else -> StateOutcome.Unavailable(result.cause ?: CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
        }
    }

    private fun transact(
        code: Int,
        jobId: String,
        requestSha256: String?,
        payload: ByteArray?,
    ): CliModelWireResult {
        val connection = supervisor.openConnection()
        if (connection is CliRuntimeConnection.Refused) return CliModelWireResult(cause = connection.cause)
        connection as CliRuntimeConnection.Opened
        return try {
            transactOn(connection.binder, code, jobId, requestSha256, payload)
        } finally {
            supervisor.closeConnection(connection)
        }
    }
}
