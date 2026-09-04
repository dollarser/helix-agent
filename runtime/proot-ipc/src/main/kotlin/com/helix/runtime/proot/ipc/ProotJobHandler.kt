package com.helix.runtime.proot.ipc

import android.os.ParcelFileDescriptor

/**
 * The submit verdict (HXA-084). A job is started EXACTLY ONCE: a known jobId or
 * a known executionId returns the existing record as [Duplicate] and never
 * restarts anything (ADR-0007: no blind replay).
 */
sealed interface ProotJobSubmitResult {
    /** The PENDING record was written; the lifecycle runs asynchronously. */
    data class Accepted(
        val record: ProotJobRecord,
    ) : ProotJobSubmitResult

    /** The job was already known (jobId or executionId); the existing record is returned. */
    data class Duplicate(
        val record: ProotJobRecord,
    ) : ProotJobSubmitResult

    /** Refused up front; the PFDs are closed by the server and the job never existed. */
    data class Rejected(
        val refusal: ProotJobRefusal,
    ) : ProotJobSubmitResult
}

/**
 * The companion-side job surface, injected into the service binder (HXA-084).
 * The implementation ([ProotJobRunner] in the companion) runs every call on its
 * single job thread; the binder thread only does the fast submit checks.
 *
 * All PFDs handed to [submit] are owned by the handler from the call onward
 * (closed in every outcome, including the rejected ones).
 */
interface ProotJobHandler {
    fun submit(
        spec: ProotJobSpec,
        inputPfd: ParcelFileDescriptor,
        outputPfd: ParcelFileDescriptor,
    ): ProotJobSubmitResult

    /** null when the job id is unknown. */
    fun query(jobId: String): ProotJobRecord?

    /** Terminal records are returned unchanged; non-terminal jobs get the kill. */
    fun cancel(jobId: String): ProotJobRecord?

    /** Terminal records are reconciled (payload deleted, tombstone stamped). */
    fun reconcile(
        jobId: String,
        reconciledAtEpochMs: Long,
    ): ProotJobRecord?
}
