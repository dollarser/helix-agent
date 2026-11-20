package com.helix.app.proot

import android.os.DeadObjectException
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real Runtime death plus Room reopen, not main-app death, low-memory pressure or a branch UI journey. */
class InPlaceRuntimeDeathDeviceTest {
    @Test fun directorySwitchAndRuntimeDeathKeepOriginalIdentityAndRejectForeignLogReaders() {
        check(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName ==
                "com.helix.agent.developer.asrvalidation",
        ) { "Runtime death validation requires the isolated asrvalidation package" }
        val f = InPlaceLogCloseoutDeviceTest.Fixture(runtimeAccessibleStorage = true)
        try {
            val original = requireNotNull(f.storage.workspaces.binding("session"))
            val root =
                f.storage.workspaces
                    .managedDirectory(original.workspaceId)
                    .toFile()
            val foreign = f.storage.sessions.create("foreign", "Foreign fixture", null, null, 3)
            f.storage.turns.start("foreign-turn", foreign.id, 4)
            val next = requireNotNull(f.storage.workspaces.binding(foreign.id))
            val nextRoot =
                f.storage.workspaces
                    .managedDirectory(next.workspaceId)
                    .toFile()
            f.start("printf 'once\\n' >> document.txt; echo before-death; sleep 60", root)
            awaitMarker(f)
            assertEquals("once\n", File(root, "document.txt").readText())
            val captured = ProotJobBindingStore(f.storage).resolve("call")
            assertEquals(root.canonicalPath, captured.getValue("workspacePath").jsonPrimitive.content)
            assertEquals(f.spec.workspaceWitness, captured.getValue("workspaceWitness").jsonPrimitive.content)
            f.storage.sessions.updateDetails("session", "Directory switched", foreign.directoryRef)
            assertEquals(
                next.workspaceId,
                f.storage.workspaces
                    .binding("session")
                    ?.workspaceId,
            )
            killRuntime(f)
            val orphan = f.terminal()
            assertOriginalOrphan(f, orphan)
            f.reopen()
            assertEquals(captured, ProotJobBindingStore(f.storage).resolve("call"))
            assertEquals(
                next.workspaceId,
                f.storage.workspaces
                    .binding("session")
                    ?.workspaceId,
            )
            assertRecoveredObservations(f)
            assertNoReplayOrFalseSuccess(f, root, nextRoot)
        } finally {
            try {
                if (f.storage.sessions.find("foreign") != null) f.storage.deleteSessionPermanently("foreign")
            } finally {
                f.close(discardUnstoredResult = true)
            }
        }
    }

    private fun assertRecoveredObservations(f: InPlaceLogCloseoutDeviceTest.Fixture) {
        repeat(3) {
            assertOriginalOrphan(f, f.terminal())
            assertForeignReadersRejected(f)
            val saved = (f.read(f.request("saved")) as ToolExecutorResult.Completed).output.jsonObject
            assertEquals("true", saved.getValue("logUnavailable").toString())
            assertFalse(saved.containsKey("exitCode"))
            assertEquals(f.spec.jobId, saved.getValue("jobId").jsonPrimitive.content)
            assertEquals(f.spec.executionId, saved.getValue("executionId").jsonPrimitive.content)
        }
    }

    private fun assertNoReplayOrFalseSuccess(
        f: InPlaceLogCloseoutDeviceTest.Fixture,
        root: File,
        nextRoot: File,
    ) {
        assertEquals(1, f.submissions)
        assertEquals("once\n", File(root, "document.txt").readText())
        assertFalse(File(nextRoot, "document.txt").exists())
        assertTrue(
            f.storage.artifacts
                .listBySession("session")
                .isEmpty(),
        )
        assertTrue(
            f.storage.artifacts
                .listBySession("foreign")
                .isEmpty(),
        )
        // An orphan has no trusted completion receipt; do not manufacture COMPLETED or persist a ZIP.
        assertEquals(
            "RUNNING",
            f.storage.toolCalls
                .resolve("row")
                .state,
        )
    }

    private fun awaitMarker(f: InPlaceLogCloseoutDeviceTest.Fixture) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val outcome = f.client.query(f.spec.jobId)
            val record = (outcome as? ProotJobClient.JobStateOutcome.Ok)?.record
            if (record?.state?.isTerminal == true) {
                markerFailure(f, "Original command terminated before Runtime death: $outcome")
            }
            if (record?.state == ProotJobState.RUNNING && f.log("live").contains("before-death")) return
            Thread.sleep(50)
        }
        markerFailure(f, "Pre-death marker deadline: ${f.client.query(f.spec.jobId)}")
    }

    private fun markerFailure(
        f: InPlaceLogCloseoutDeviceTest.Fixture,
        reason: String,
    ): Nothing {
        val job = File(f.context.filesDir, "runtime/jobs/${f.spec.jobId}")
        val diagnostic =
            buildString {
                appendLine(reason)
                appendLine("workspace=${f.spec.workspacePath}; witness=${f.spec.workspaceWitness}")
                appendLine("installation=${ProotRuntimeSupervisor(f.context).installation()}")
                listOf("failure.txt", "_stdout.txt", "_stderr.txt").forEach { name ->
                    val file = File(job, name)
                    val content =
                        if (file.isFile) {
                            file.bufferedReader().use { it.readText().take(4096) }
                        } else {
                            "<unavailable>"
                        }
                    appendLine("$name: $content")
                }
                appendLine("live stdout=${f.log("live")}")
                appendLine("live stderr=${f.log("live", "stderr")}")
            }
        // Preserve synthetic failure facts before the fixture ACK/cleanup removes Runtime scratch.
        File(f.context.noBackupFilesDir, "in-place-runtime-death-diagnostic.txt").writeText(diagnostic)
        error(diagnostic)
    }

    private fun assertOriginalOrphan(
        f: InPlaceLogCloseoutDeviceTest.Fixture,
        record: ProotJobRecord,
    ) {
        assertEquals(ProotJobState.ORPHANED, record.state)
        assertEquals(f.spec.jobId, record.jobId)
        assertEquals(f.spec.executionId, record.executionId)
        assertEquals(f.spec.inputManifestSha256, record.inputManifestSha256)
        assertNull(record.exitCode)
    }

    private fun assertForeignReadersRejected(f: InPlaceLogCloseoutDeviceTest.Fixture) {
        listOf("live", "saved").forEach { source ->
            val request = f.request(source).copy(sessionId = "foreign", turnId = "foreign-turn")
            assertTrue(f.read(request) is ToolExecutorResult.Failed)
            // A forged current turn from the owner must not make the foreign session authoritative.
            assertTrue(f.read(request.copy(turnId = "turn")) is ToolExecutorResult.Failed)
        }
    }

    private fun killRuntime(f: InPlaceLogCloseoutDeviceTest.Fixture) {
        val supervisor = ProotRuntimeSupervisor(f.context)
        val connection = supervisor.openConnection() as ProotConnection.Opened
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            try {
                connection.binder.transact(ProotRuntimeProtocol.TX_DEBUG_SELF_KILL, data, reply, 0)
            } catch (_: DeadObjectException) {
                // The debug kill can terminate the Runtime before it returns a reply.
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (connection.binder.isBinderAlive && System.nanoTime() < deadline) Thread.sleep(25)
            assertFalse("The original Runtime Binder must actually die", connection.binder.isBinderAlive)
        } finally {
            data.recycle()
            reply.recycle()
            supervisor.closeConnection()
        }
    }
}
