package com.helix.app.proot

import android.os.RemoteException
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.ProotJobRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Execution success and cleanup receipt are separate: an unavailable ACK never causes command replay. */
internal class ProotResultCommitter(
    private val storage: HelixStorage,
    private val store: ProotResultStore,
    private val acknowledge: (ProotJobRecord) -> ProotJobRecord?,
) {
    fun commit(
        turnId: String,
        callId: String,
        record: ProotJobRecord,
        archive: File,
    ): Boolean {
        archive.inputStream().use { store.persist(turnId, callId, record, it) }
        val receipt =
            try {
                acknowledge(record)
            } catch (_: RemoteException) {
                null
            }
        receipt?.let {
            check(it.terminalCommit == record.terminalCommit && it.reconciledAtEpochMs != null)
        }
        val acknowledged = receipt != null
        val payload =
            buildJsonObject {
                put("jobId", record.jobId)
                put("toolCallId", callId)
                put("terminalCommit", record.terminalCommit)
                put("acknowledged", acknowledged)
            }
        storage.auditEvents.append(
            "proot-result-ack-${UUID.randomUUID()}",
            storage.turns.resolve(turnId).sessionId,
            "proot.result_ack",
            "platform",
            payload.toString(),
            System.currentTimeMillis(),
        )
        return acknowledged
    }
}
