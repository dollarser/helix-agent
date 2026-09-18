package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.ExecutionTargetType
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

class DetachedJobCollectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun importFailureKeepsOwnerAndRetriesOnlyTheOriginalArchive() {
        ensureInstalledRuntime(context)
        Fixture(context).use { f ->
            finish(f)
            var attempts = 0
            var settled = 0
            val collector =
                DetachedJobCollection(context, f.storage, f.ownership, { binding, record, archive ->
                    attempts++
                    assertEquals(f.job.binding, binding)
                    assertEquals(ProotJobState.SUCCEEDED, record.state)
                    val preview =
                        ProotResultPreview.read(
                            requireNotNull(archive),
                            File(context.cacheDir, "collection-preview"),
                        )
                    assertTrue(preview.files.any { it.path == "result.txt" })
                    if (attempts == 1) throw IOException("injected output import failure")
                }, { _, _ -> settled++ })
            val executor = f.ownership.guard(collector.executor())
            assertThrows(IOException::class.java) { executor.execute(f.call) }
            assertEquals(f.owner, f.ownership.retainedOwner())
            assertNull(f.ownership.acquire("unrelated-writer"))
            assertEquals(0, settled)
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
            assertNull(f.ownership.retainedOwner())
            assertEquals(2, attempts)
            assertEquals(1, settled)
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
            assertEquals(2, attempts)
        }
    }

    @Test fun budgetRetryUsesDurableReceiptAndForeignSessionNeverImports() {
        ensureInstalledRuntime(context)
        Fixture(context).use { f ->
            finish(f)
            var imports = 0
            var settlements = 0

            fun collector() =
                DetachedJobCollection(context, f.storage, f.ownership, { _, _, _ ->
                    imports++
                }, { _, _ ->
                    settlements++
                    if (settlements == 1) throw IOException("injected Goal settlement failure")
                })
            val first = f.ownership.guard(collector().executor())
            assertThrows(IllegalStateException::class.java) { first.execute(f.call.copy(sessionId = "foreign")) }
            assertEquals(0, imports)
            assertThrows(IOException::class.java) { first.execute(f.call) }
            assertEquals(f.owner, f.ownership.retainedOwner())
            val reloaded = f.ownership.guard(collector().executor())
            assertTrue(reloaded.execute(f.call) is ToolExecutorResult.Completed)
            assertEquals(1, imports)
            assertEquals(2, settlements)
            assertNull(f.ownership.retainedOwner())
        }
    }

    @Test fun failedExecutionSettlesItsOriginalFailureWithoutInventingAnOutputArchive() {
        ensureInstalledRuntime(context)
        Fixture(context, "exit 7").use { f ->
            finish(f, ProotJobState.FAILED)
            var effects = 0
            val collector =
                DetachedJobCollection(context, f.storage, f.ownership, { _, record, archive ->
                    assertEquals(ProotJobState.FAILED, record.state)
                    assertEquals(7, record.exitCode)
                    assertNull(archive)
                    effects++
                }, { _, _ -> })
            assertTrue(f.ownership.guard(collector.executor()).execute(f.call) is ToolExecutorResult.Completed)
            assertEquals(1, effects)
            assertNull(f.ownership.retainedOwner())
        }
    }

    private fun finish(
        f: Fixture,
        expected: ProotJobState = ProotJobState.SUCCEEDED,
    ) {
        val client = DetachedJobClient(context)
        assertTrue(f.job.submit(client).accepted)
        val until = android.os.SystemClock.elapsedRealtime() + 20_000
        var record = client.query(f.job.binding).record
        while (record?.state?.isTerminal != true && android.os.SystemClock.elapsedRealtime() < until) {
            Thread.sleep(100)
            record = client.query(f.job.binding).record
        }
        assertEquals(expected, record?.state)
    }

    private class Fixture(
        private val context: HelixApplication,
        script: String = "printf collection-proof > result.txt",
    ) : AutoCloseable {
        val job = DetachedJobFixture(context, script)
        private val name = "bound-control-${UUID.randomUUID()}"
        private val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        val owner = ExecutionOwnership.Owner(job.binding.executionId, job.binding.jobId)
        private var retained: ExecutionOwnership.Owner? = null
        val ownership =
            ExecutionOwnership(
                object : ExecutionOwnership.Store {
                    override fun read() = retained

                    override fun compareAndSet(
                        expected: ExecutionOwnership.Owner?,
                        replacement: ExecutionOwnership.Owner?,
                    ): Boolean {
                        if (retained != expected) return false
                        retained = replacement
                        return true
                    }
                },
            )
        val call =
            ExecutableToolCall(
                "control-call",
                "code.linux.job.collect",
                "1",
                buildJsonObject { put("originalCallId", job.binding.toolCallId) },
                ExecutionTargetType.LOCAL_PROOT,
                Instant.now().plusSeconds(30),
                NoCancellation,
                job.binding.sessionId,
                job.binding.turnId,
            )

        init {
            val binding = job.binding
            storage.sessions.create(binding.sessionId, "Control test", null, null, 1)
            storage.turns.start(binding.turnId, binding.sessionId, 2)
            storage.toolCalls.append(
                "stored-call",
                binding.turnId,
                binding.toolCallId,
                DetachedJobTools.START,
                "1",
                "{}",
                "RUNNING",
            )
            ProotJobBindingStore(storage).recordDetached(
                LinuxRunTool.ParsedLinuxCall(
                    binding.toolCallId,
                    binding.turnId,
                    binding.sessionId,
                    job.spec.command,
                    "",
                    emptyMap(),
                    emptyList(),
                    null,
                    System.currentTimeMillis() + 60_000,
                ),
                job.spec,
            )
            requireNotNull(ownership.acquire("launch")).use { check(it.retain(owner)) }
        }

        override fun close() {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
            job.directory.deleteRecursively()
        }
    }
}
