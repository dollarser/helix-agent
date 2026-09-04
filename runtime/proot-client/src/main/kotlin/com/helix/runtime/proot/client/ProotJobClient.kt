package com.helix.runtime.proot.client

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.helix.runtime.proot.ipc.ProotIpcException
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobRefusal
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.runtime.proot.ipc.UnavailableCause

/**
 * The main app's PRoot job channel (HXA-084; architecture doc section 6.5).
 *
 * Every call is a cold bind through [ProotRuntimeSupervisor] (ADR-0007: the
 * companion is started only by an approved job or the user's verification
 * click; the binding closes as soon as the transaction completes). After a
 * Binder death the ONLY sanctioned actions are [query]/[reconcile] — never a
 * blind resubmit; a terminal proof recovers the result only when its
 * [ProotJobRecord.inputManifestSha256] matches what the caller holds.
 *
 * No foreground service is ever started here, and no computation in this path
 * claims the `dataSync` type (HXA-084: 任意计算不得冒充 dataSync). A job whose
 * main-app host process goes to the background without a legitimate FGS type
 * must be [cancel]led by the caller (the wake-lock question is deliberately
 * left to the real-device work of HXA-086/088).
 *
 * All methods block; call them off the main thread.
 */
class ProotJobClient(
    private val supervisor: ProotRuntimeSupervisor,
) {
    /** A stable query/cancel/reconcile outcome. */
    sealed interface JobStateOutcome {
        data class Ok(
            val record: ProotJobRecord,
        ) : JobStateOutcome

        data object Unknown : JobStateOutcome

        data class Refused(
            val cause: UnavailableCause,
        ) : JobStateOutcome
    }

    /** The submit verdict as seen from the client (server result + bind failures). */
    sealed interface SubmitOutcome {
        data class Accepted(
            val record: ProotJobRecord,
        ) : SubmitOutcome

        data class Duplicate(
            val record: ProotJobRecord,
        ) : SubmitOutcome

        data class Rejected(
            val refusal: ProotJobRefusal,
        ) : SubmitOutcome

        data class Unavailable(
            val cause: UnavailableCause,
        ) : SubmitOutcome
    }

    /** The await loop's verdict. */
    sealed interface AwaitOutcome {
        /** The job reached a terminal state (or was already terminal). */
        data class Terminal(
            val record: ProotJobRecord,
        ) : AwaitOutcome

        /**
         * The record is terminal but its evidence has expired (30-day
         * retention): the payload is gone and the main app must park the run
         * as `INTERRUPTED` — the terminal proof alone is not enough to
         * restore a result.
         */
        data class Interrupted(
            val record: ProotJobRecord,
        ) : AwaitOutcome

        /** The job id was never known to the Runtime. */
        data object Unknown : AwaitOutcome

        /** The await window elapsed with the job still non-terminal. */
        data object TimedOut : AwaitOutcome

        data class Unavailable(
            val cause: UnavailableCause,
        ) : AwaitOutcome
    }

    // ------------------------------------------------------------------ submit

    /**
     * Submits [spec]. The caller OWNS both PFDs from this call: the server
     * closes its copy in every outcome (accepted, duplicate, rejected); on an
     * UNDELIVERED submission (bind refused, dead binder) this method closes
     * the client-side copies too, so the caller's ownership promise holds
     * either way. The caught wire/death exceptions ARE the answer (a stable
     * cause); nothing is lost by mapping them instead of rethrowing.
     */
    @Suppress("SwallowedException")
    fun submit(
        spec: ProotJobSpec,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
    ): SubmitOutcome {
        val connection = supervisor.openConnection()
        if (connection is ProotConnection.Refused) {
            inputPfd.closeQuietly()
            outputPfd.closeQuietly()
            return SubmitOutcome.Unavailable(connection.cause)
        }
        val binder = (connection as ProotConnection.Opened).binder
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
                ProotJobWire.writeSpec(data, spec, inputPfd, outputPfd)
                binder.transact(ProotRuntimeProtocol.TX_JOB_SUBMIT, data, reply, 0)
                val (status, payload) = ProotJobWire.readJobReply(reply)
                when (status) {
                    ProotRuntimeProtocol.REPLY_JOB_ACCEPTED -> {
                        SubmitOutcome.Accepted(decodeRecord(payload))
                    }

                    ProotRuntimeProtocol.REPLY_JOB_DUPLICATE -> {
                        SubmitOutcome.Duplicate(decodeRecord(payload))
                    }

                    ProotRuntimeProtocol.REPLY_JOB_REJECTED -> {
                        SubmitOutcome.Rejected(refusalOf(payload))
                    }

                    // A production companion always has a job handler; this reply
                    // means the peer is not the expected implementation.
                    ProotRuntimeProtocol.REPLY_JOB_UNAVAILABLE -> {
                        SubmitOutcome.Unavailable(UnavailableCause.PROTOCOL_MISMATCH)
                    }

                    else -> {
                        SubmitOutcome.Unavailable(UnavailableCause.PROTOCOL_MISMATCH)
                    }
                }
            } catch (e: ProotIpcException) {
                inputPfd.closeQuietly()
                outputPfd.closeQuietly()
                SubmitOutcome.Unavailable(UnavailableCause.PROTOCOL_MISMATCH)
            } catch (e: DeadObjectException) {
                inputPfd.closeQuietly()
                outputPfd.closeQuietly()
                SubmitOutcome.Unavailable(UnavailableCause.DEAD_OBJECT)
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            supervisor.closeConnection()
        }
    }

    // ------------------------------------------------------------------ control

    fun query(jobId: String): JobStateOutcome = transactJobId(ProotRuntimeProtocol.TX_JOB_QUERY, jobId)

    fun cancel(jobId: String): JobStateOutcome = transactJobId(ProotRuntimeProtocol.TX_JOB_CANCEL, jobId)

    fun reconcile(jobId: String): JobStateOutcome = transactJobId(ProotRuntimeProtocol.TX_JOB_RECONCILE, jobId)

    /**
     * Polls [jobId] until it is terminal, its evidence expired (park
     * `INTERRUPTED`), unknown, or [timeoutMs] elapses. [shouldContinue] lets
     * the caller stop early (e.g. the user cancelled, or the app is going to
     * the background without a legitimate FGS type).
     */
    @Suppress("ReturnCount", "NestedBlockDepth") // one poll step per return; the loop IS the contract

    fun awaitTerminal(
        jobId: String,
        pollIntervalMs: Long = 500L,
        timeoutMs: Long = 3_600_000L,
        shouldContinue: () -> Boolean = { true },
    ): AwaitOutcome {
        val start = System.currentTimeMillis()
        while (shouldContinue()) {
            if (System.currentTimeMillis() - start > timeoutMs) return AwaitOutcome.TimedOut
            val step = awaitOneStep(jobId)
            if (step != null) return step
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return AwaitOutcome.TimedOut
            }
        }
        return AwaitOutcome.TimedOut
    }

    /** One poll: the verdict when the job settled (or the query failed), null while it is still live. */
    private fun awaitOneStep(jobId: String): AwaitOutcome? =
        when (val outcome = query(jobId)) {
            is JobStateOutcome.Ok -> {
                val record = outcome.record
                if (!record.state.isTerminal) {
                    null
                } else if (record.evidenceExpired) {
                    AwaitOutcome.Interrupted(record)
                } else {
                    AwaitOutcome.Terminal(record)
                }
            }

            is JobStateOutcome.Unknown -> {
                AwaitOutcome.Unknown
            }

            is JobStateOutcome.Refused -> {
                AwaitOutcome.Unavailable(outcome.cause)
            }
        }

    // ------------------------------------------------------------------ internals

    // Same mapping contract as [submit]: exception -> stable cause.
    @Suppress("SwallowedException")
    private fun transactJobId(
        code: Int,
        jobId: String,
    ): JobStateOutcome {
        val connection = supervisor.openConnection()
        if (connection is ProotConnection.Refused) return JobStateOutcome.Refused(connection.cause)
        val binder = (connection as ProotConnection.Opened).binder
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
                data.writeString(jobId)
                binder.transact(code, data, reply, 0)
                val (status, payload) = ProotJobWire.readJobReply(reply)
                when (status) {
                    ProotRuntimeProtocol.REPLY_JOB_STATE -> JobStateOutcome.Ok(decodeRecord(payload))
                    ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND -> JobStateOutcome.Unknown
                    else -> JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)
                }
            } catch (e: ProotIpcException) {
                JobStateOutcome.Refused(UnavailableCause.PROTOCOL_MISMATCH)
            } catch (e: DeadObjectException) {
                JobStateOutcome.Refused(UnavailableCause.DEAD_OBJECT)
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            supervisor.closeConnection()
        }
    }

    // A malformed record maps to the stable PROTOCOL_MISMATCH, never a crash.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    private fun decodeRecord(payload: String?): ProotJobRecord {
        val document =
            payload
                ?: throw ProotIpcException("job record payload is null")
        return try {
            ProotJobRecordCodec.parse(document)
        } catch (e: ProotIpcException) {
            throw e
        } catch (e: Exception) {
            throw ProotIpcException("job record failed to parse: ${e.message?.take(120)}", e)
        }
    }

    private fun refusalOf(payload: String?): ProotJobRefusal =
        ProotJobRefusal.entries.firstOrNull { it.wire == payload } ?: ProotJobRefusal.INVALID_SPEC

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed close of a dying fd is nothing left to do

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (e: Exception) {
            // the fd is already gone; nothing left to do
        }
    }
}
