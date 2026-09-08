package com.helix.app.proot

import android.content.Context
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotResultClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File
import java.util.UUID

/** Real Runtime job and Binder; only the interrupted Turn ownership is seeded for this bounded check. */
internal fun verifyProotDurableRecovery(
    context: Context,
    record: ProotJobRecord,
) {
    val name = "proot-durable-${UUID.randomUUID()}"
    val root = File(context.cacheDir, name)
    val storage = HelixStorage.open(context, name, File(root, "content"))
    try {
        seedProotRecoveryOwner(storage, record)
        val supervisor = ProotRuntimeSupervisor(context)
        val jobs = ProotJobClient(supervisor)
        val results = ProotResultClient(supervisor)
        val store = ProotResultStore(storage, root, File(root, "scratch"))
        val recovery =
            ProotResultRecovery(
                storage,
                store,
                { (jobs.query(it) as? ProotJobClient.JobStateOutcome.Ok)?.record },
                results::fetch,
                results::acknowledge,
            )
        repeat(2) {
            requireNotNull(results.fetch(record)).use { assertEquals(record, it.record) }
        }
        assertNull(results.acknowledge(record.copy(stdoutBytes = record.stdoutBytes + 1)))
        assertEquals(record, (jobs.query(record.jobId) as ProotJobClient.JobStateOutcome.Ok).record)
        val recovered = requireNotNull(recovery.recover("turn", "call", localOnly = false))
        assertEquals(true, recovered.acknowledged)
        val extracted = File(root, "visible")
        assertEquals(record.outputManifestSha256, ZipJobExtractor.extract(recovered.file, extracted).manifestSha256)
        assertEquals("MAIN_APP_OUT\n", File(extracted, "stdout.txt").readText())
        val confirmed = (jobs.query(record.jobId) as ProotJobClient.JobStateOutcome.Ok).record
        assertNotNull(confirmed.reconciledAtEpochMs)
        assertEquals(record.terminalCommit, confirmed.terminalCommit)
        assertNull(results.fetch(confirmed))
        assertEquals(confirmed, results.acknowledge(record))
        assertEquals(recovered.file, requireNotNull(recovery.recover("turn", "call", true)).file)
        assertEquals(1, storage.artifacts.listBySession("session").size)
    } finally {
        storage.close()
        context.deleteDatabase(name)
        root.deleteRecursively()
    }
}

private fun seedProotRecoveryOwner(
    storage: HelixStorage,
    record: ProotJobRecord,
) {
    storage.sessions.create("session", "PRoot result fixture", null, null, 1)
    val turn = storage.turns.start("turn", "session", 2)
    storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 3, "PROCESS_DEATH")
    storage.toolCalls.append("stored-call", "turn", "call", "bash", "1", "{}", "INTERRUPTED")
    val payload =
        buildJsonObject {
            put("version", 1)
            put("toolCallId", "call")
            put("turnId", "turn")
            put("jobId", record.jobId)
            put("executionId", record.executionId)
            put("inputManifestSha256", record.inputManifestSha256)
        }
    storage.auditEvents.append("proot-job-call", "session", "proot.job_prepared", "platform", payload.toString(), 3)
}
