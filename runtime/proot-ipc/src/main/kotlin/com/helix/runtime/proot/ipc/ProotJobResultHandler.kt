package com.helix.runtime.proot.ipc

import android.os.ParcelFileDescriptor

/** Optional additive result transport; older handlers remain explicitly unavailable. */
interface ProotJobResultHandler {
    fun fetchResult(jobId: String): ProotJobArchive?

    fun acknowledgeResult(
        jobId: String,
        terminalCommit: String,
        now: Long,
    ): ProotJobRecord?
}

/** Receiver owns this read-only descriptor; bytes still require manifest verification before import. */
class ProotJobArchive(
    val record: ProotJobRecord,
    val descriptor: ParcelFileDescriptor,
) : AutoCloseable {
    override fun close() = descriptor.close()
}
