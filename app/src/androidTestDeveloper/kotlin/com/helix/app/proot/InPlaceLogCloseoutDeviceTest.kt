package com.helix.app.proot

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolCallState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.core.LiveJobWorkspace
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Synthetic, uniquely owned directories/Room only. Real Binder/PRoot; never clears application data. */
class InPlaceLogCloseoutDeviceTest {
    @Test fun originalFileIsRegisteredAndSavedLogSurvivesRoomReopen() =
        withFixture { f ->
            val record = f.run("printf original > document.txt; echo saved-log")
            assertEquals(ProotJobState.SUCCEEDED, record.state)
            f.persist(record)
            assertEquals("original", File(f.workspace, "document.txt").readText())
            val ref = "scope:app:${f.workspace.name}/document.txt"
            assertEquals(
                FileContentStore.sha256Hex(File(f.workspace, "document.txt")),
                f.storage.artifacts
                    .findBySessionAndPath("session", ref)!!
                    .sha256,
            )
            assertTrue(f.log("saved").contains("saved-log"))
            f.reopen()
            assertTrue(f.log("saved").contains("saved-log"))
            assertEquals(1, f.submissions)
            assertEquals("original", File(f.workspace, "document.txt").readText())
        }

    @Test fun failureLogsAreReadableAndCannotCrossSession() =
        withFixture { f ->
            val record = f.run("echo failed-output; echo failed-error >&2; exit 7")
            assertEquals(ProotJobState.FAILED, record.state)
            assertEquals(7, record.exitCode)
            f.persist(record)
            assertTrue(f.log("saved", "stderr").contains("failed-error"))
            assertTrue(f.log("saved").contains("failed-output"))
            assertTrue(
                f.storage.artifacts
                    .listBySession("session")
                    .all { it.purpose == "RUNTIME_EVIDENCE" },
            )
            f.storage.sessions.create("foreign", "Foreign fixture", null, null, 1)
            f.storage.turns.start("foreign-turn", "foreign", 2)
            val request = f.request("saved").copy(sessionId = "foreign", turnId = "foreign-turn")
            assertTrue(f.read(request) is ToolExecutorResult.Failed)
            f.reopen()
            assertTrue(f.log("saved", "stderr").contains("failed-error"))
            assertEquals(1, f.submissions)
        }

    @Test fun liveObservationAndCancellationDoNotReplayOrRollbackWrites() =
        withFixture { f ->
            f.start("echo marker > document.txt; echo running-preview; sleep 30; echo forbidden-replay > forbidden.txt")
            val deadline = System.nanoTime() + 15_000_000_000L
            var visible = false
            while (!visible && System.nanoTime() < deadline) {
                visible = f.log("live").contains("running-preview")
                if (!visible) Thread.sleep(50)
            }
            assertTrue("Expected live stdout before exit", visible)
            f.client.cancel(f.spec.jobId)
            val record = f.terminal()
            assertEquals(ProotJobState.CANCELLED, record.state)
            f.persist(record)
            assertTrue(File(f.workspace, "document.txt").isFile)
            assertFalse(File(f.workspace, "forbidden.txt").exists())
            assertTrue(f.log("saved").contains("running-preview"))
            assertEquals(1, f.submissions)
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    internal class Fixture(
        private val sharedStorage: HelixStorage? = null,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val id =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        private val database = "closeout-$id"
        private val scratch = File(context.cacheDir, database).apply { mkdirs() }
        val workspace = File(context.filesDir, "workspaces/app/closeout-$id").apply { mkdirs() }.canonicalFile
        var storage = sharedStorage ?: HelixStorage.open(context, database, File(scratch, "content"))
        private val supervisor = ProotRuntimeSupervisor(context)
        val client = ProotJobClient(supervisor)
        lateinit var spec: ProotJobSpec
        private val output = File(scratch, "output.zip")
        var submissions = 0

        private val store get() =
            ProotResultStore(storage, File(context.filesDir, "workspaces/app"), File(scratch, "verify"))

        init {
            ensureInstalledRuntime(context)
            storage.sessions.create("session", "Closeout fixture", null, null, 1)
            storage.turns.start("turn", "session", 2)
            storage.toolCalls.append("row", "turn", "call", "bash", "1", "{}", "RUNNING")
        }

        fun run(command: String): ProotJobRecord {
            start(command)
            return terminal()
        }

        fun start(command: String) {
            val manifest = JobManifestCodec.encode(JobManifest(emptyList()))
            val input = File(scratch, "input.zip")
            JobZipWriter(input.outputStream()).use { it.writeManifest(manifest) }
            spec =
                ProotJobSpec(
                    "exec_$id",
                    "job_$id",
                    ProotJobCommand.Script(command),
                    "",
                    emptyMap(),
                    60000,
                    1024 * 1024,
                    FileContentStore.sha256Hex(manifest.toByteArray()),
                    workspacePath = workspace.path,
                    workspaceWitness = LiveJobWorkspace.witness(workspace),
                    artifactPaths = listOf("document.txt"),
                )
            val call =
                LinuxRunTool.ParsedLinuxCall(
                    "call",
                    "turn",
                    "session",
                    spec.command,
                    "",
                    emptyMap(),
                    emptyList(),
                    null,
                    System.currentTimeMillis() + 60000,
                    workspaceReference = "scope:app:${workspace.name}",
                    artifactPaths = spec.artifactPaths,
                )
            ProotJobBindingStore(storage).record(call, spec)
            val source = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val target =
                ParcelFileDescriptor.open(
                    output,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                )
            submissions++
            assertTrue(client.submit(spec, source, target) is ProotJobClient.SubmitOutcome.Accepted)
        }

        fun terminal(): ProotJobRecord =
            (
                client.awaitTerminal(spec.jobId, timeoutMs = 60000) as
                    ProotJobClient.AwaitOutcome.Terminal
            ).record

        fun persist(record: ProotJobRecord) {
            output.inputStream().use { store.persist("turn", "call", record, it) }
            val call = storage.toolCalls.resolve("row")
            storage.toolCalls.updateState(
                call,
                if (record.state ==
                    ProotJobState.SUCCEEDED
                ) {
                    ToolCallState.COMPLETED
                } else {
                    ToolCallState.FAILED
                },
            )
        }

        fun reopen() {
            storage.close()
            storage = HelixStorage.open(context, database, File(scratch, "content"))
        }

        fun request(
            source: String,
            stream: String = "stdout",
        ) = ExecutableToolCall(
            "reader",
            ProotLogTool.NAME,
            "1",
            buildJsonObject {
                put("logRef", "turn/call")
                put("source", source)
                put("stream", stream)
                put("offset", 0)
            },
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(5),
            NoCancellation,
            "session",
            "turn",
        )

        fun read(call: ExecutableToolCall) =
            ProotLogTool.read(storage, store, call, { emptySet() }, File(scratch, "read"))

        fun log(
            source: String,
            stream: String = "stdout",
        ): String =
            (
                read(
                    request(source, stream),
                ) as ToolExecutorResult.Completed
            ).output.jsonObject.getValue("content").jsonPrimitive.content

        fun retainForProcessRestart(): kotlinx.serialization.json.JsonObject {
            val anchor =
                buildJsonObject {
                    put("database", database)
                    put("workspaceName", workspace.name)
                    put("jobId", spec.jobId)
                    put("inputHash", spec.inputManifestSha256)
                }
            com.helix.runtime.proot.client
                .ProotResultClient(supervisor)
                .acknowledge(terminal())
            storage.close()
            supervisor.closeConnection()
            return anchor
        }

        fun close() {
            if (::spec.isInitialized) {
                client.cancel(spec.jobId)
                val record = terminal()
                if (store.readLocal("turn", "call") != null) {
                    com.helix.runtime.proot.client
                        .ProotResultClient(supervisor)
                        .acknowledge(record)
                }
                File(context.filesDir, "workspaces/app/.helix/proot-results/${spec.jobId}.zip").delete()
            }
            supervisor.closeConnection()
            storage.deleteSessionPermanently("session")
            if (sharedStorage == null) {
                storage.close()
                context.deleteDatabase(database)
            }
            workspace.deleteRecursively()
            scratch.deleteRecursively()
        }
    }
}
