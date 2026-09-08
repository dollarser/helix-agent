package com.helix.runtime.proot.app

import android.os.ParcelFileDescriptor
import com.helix.runtime.proot.ipc.ProotJobArchive
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol

internal class ProotResultArchiveStore(
    private val store: ProotJobStore,
) {
    fun open(jobId: String): ProotJobArchive? {
        com.helix.runtime.proot.ipc.ProotJobRecordCodec
            .checkJobId(jobId)
        val record = store.load(jobId) ?: return null
        val file = store.outputFile(jobId)
        val readable =
            record.state == ProotJobState.SUCCEEDED && !record.evidenceExpired &&
                record.reconciledAtEpochMs == null && record.outputManifestSha256 != null
        val recent =
            (record.terminalAtEpochMs ?: 0L) >
                System.currentTimeMillis() - ProotJobStore.UNRECONCILED_EVIDENCE_TTL_MS
        val hasArchive = file.isFile && file.length() in 1..ProotRuntimeProtocol.MAX_RESULT_ARCHIVE_BYTES
        return if (readable && recent && hasArchive) {
            ProotJobArchive(record, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        } else {
            null
        }
    }
}
