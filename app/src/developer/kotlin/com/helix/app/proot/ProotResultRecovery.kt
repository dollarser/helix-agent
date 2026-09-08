package com.helix.app.proot

import android.content.Context
import android.os.ParcelFileDescriptor
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotResultClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.ipc.ProotJobArchive
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Explicit recovery of the original job. A local archive remains usable when acknowledgement is unavailable. */
internal class ProotResultRecovery(
    private val storage: HelixStorage,
    private val store: ProotResultStore,
    private val query: (String) -> ProotJobRecord?,
    private val fetch: (ProotJobRecord) -> ProotJobArchive?,
    private val acknowledge: (ProotJobRecord) -> ProotJobRecord?,
) {
    fun recover(
        turnId: String,
        callId: String,
        localOnly: Boolean,
    ): ProotRecoveredArchive? {
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(turnId, callId))
        check(prootRecoveryEligible(storage.turns.resolve(turnId).state, call.state))
        val local = store.readLocal(turnId, callId)
        if (localOnly) return local?.let { ProotRecoveredArchive(it, acknowledged = false) }
        val job =
            ProotJobBindingStore(storage)
                .resolve(callId)
                .getValue("jobId")
                .jsonPrimitive.content
        val record = requireNotNull(query(job)) { "Runtime unavailable" }
        check(record.state == ProotJobState.SUCCEEDED && !record.evidenceExpired)
        val file =
            if (local != null) {
                local.inputStream().use { store.persist(turnId, callId, record, it) }
            } else {
                fetchAndPersist(turnId, callId, record)
            }
        val receipt =
            try {
                acknowledge(record)
            } catch (_: android.os.RemoteException) {
                null
            }
        receipt?.let {
            check(it.terminalCommit == record.terminalCommit && it.reconciledAtEpochMs != null)
        }
        return ProotRecoveredArchive(file, acknowledged = receipt != null)
    }

    private fun fetchAndPersist(
        turnId: String,
        callId: String,
        record: ProotJobRecord,
    ): File {
        val archive = requireNotNull(fetch(record)) { "Result unavailable" }
        archive.use {
            check(it.record == record)
            ParcelFileDescriptor.AutoCloseInputStream(it.descriptor).use { input ->
                return store.persist(turnId, callId, record, input)
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            storage: HelixStorage,
        ): ProotResultRecovery {
            val supervisor = ProotRuntimeSupervisor(context)
            val jobs = ProotJobClient(supervisor)
            val results = ProotResultClient(supervisor)
            val store =
                ProotResultStore(
                    storage,
                    File(context.filesDir, "workspaces/app"),
                    File(context.cacheDir, "proot-results"),
                )
            return ProotResultRecovery(
                storage,
                store,
                { (jobs.query(it) as? ProotJobClient.JobStateOutcome.Ok)?.record },
                results::fetch,
                results::acknowledge,
            )
        }
    }
}

internal data class ProotRecoveredArchive(
    val file: File,
    val acknowledged: Boolean,
)
