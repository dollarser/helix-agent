package com.helix.app.proot

import android.content.Context
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID

/** Seeded Room ownership around a real normal ProductionLinuxExecutor run. */
internal class NormalProotResultFixture(
    private val context: Context,
) : AutoCloseable {
    private val name = "normal-proot-${UUID.randomUUID()}"
    private val root = File(context.cacheDir, name)
    private val storage = HelixStorage.open(context, name, File(root, "content"))
    private val store = ProotResultStore(storage, root, File(root, "scratch"))
    private var savedCall: String? = null
    private val client =
        com.helix.runtime.proot.client.ProotResultClient(
            com.helix.runtime.proot.client
                .ProotRuntimeSupervisor(context),
        )
    private var savedRecord: ProotJobRecord? = null

    init {
        storage.sessions.create("session", "Normal result fixture", null, null, 1)
        storage.turns.start("turn", "session", 2)
        storage.turns.updateState(
            storage.turns.resolve("turn"),
            com.helix.core.model.TurnState.BUILDING_CONTEXT,
            0,
            2,
            null,
        )
    }

    fun bind(
        call: LinuxRunTool.ParsedLinuxCall,
        spec: ProotJobSpec,
    ) {
        storage.toolCalls.append("stored", "turn", call.toolCallId, "code.linux.run", "1", "{}", "RUNNING")
        ProotJobBindingStore(storage).record(call.copy(turnId = "turn"), spec)
    }

    fun persist(
        call: LinuxRunTool.ParsedLinuxCall,
        record: ProotJobRecord,
        archive: File,
    ) {
        assertTrue(
            ProotResultCommitter(storage, store, client::acknowledge).commit("turn", call.toolCallId, record, archive),
        )
        savedRecord = record
        savedCall = call.toolCallId
    }

    fun recover(
        callId: String,
        jobId: String,
    ) {
        storage.toolCalls.updateState(
            requireNotNull(storage.toolCalls.byTurnAndCallId("turn", callId)),
            com.helix.core.model.ToolCallState.NEEDS_REVIEW,
        )
        storage.turns.updateState(
            storage.turns.resolve("turn"),
            com.helix.core.model.TurnState.FAILED,
            0,
            3,
            "SIDE_EFFECT_UNKNOWN",
        )
        val jobs =
            com.helix.runtime.proot.client.ProotJobClient(
                com.helix.runtime.proot.client
                    .ProotRuntimeSupervisor(context),
            )
        val recovery =
            ProotResultRecovery(
                storage,
                store,
                { (jobs.query(it) as? com.helix.runtime.proot.client.ProotJobClient.JobStateOutcome.Ok)?.record },
                client::fetch,
                client::acknowledge,
            )
        val recovered = requireNotNull(recovery.recover("turn", callId, false))
        assertTrue(recovered.acknowledged)
        assertEquals("TOOL_OUT\n", ProotResultPreview.read(recovered.file, File(root, "preview")).stdout)
        assertEquals("FAILED", storage.turns.resolve("turn").state)
        val record = (jobs.query(jobId) as com.helix.runtime.proot.client.ProotJobClient.JobStateOutcome.Ok).record
        org.junit.Assert.assertNull(client.fetch(record))
        assertEquals(1, storage.artifacts.listBySession("session").size)
    }

    fun verify() {
        val file = requireNotNull(store.readLocal("turn", requireNotNull(savedCall)))
        assertTrue(file.isFile)
        org.junit.Assert.assertNull(client.fetch(requireNotNull(savedRecord)))
        assertEquals(1, storage.artifacts.listBySession("session").size)
        val preview = ProotResultPreview.read(file, File(root, "preview"))
        assertEquals("TOOL_OUT\n", preview.stdout)
    }

    override fun close() {
        storage.close()
        context.deleteDatabase(name)
        root.deleteRecursively()
    }
}
