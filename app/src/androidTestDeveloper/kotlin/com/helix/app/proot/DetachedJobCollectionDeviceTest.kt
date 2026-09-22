package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.approval.SessionPermissionService
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val pending =
                requireNotNull(
                    CommandResultBrowser.browseSync(f.storage, f.job.binding.turnId, f.job.binding.toolCallId),
                )
            assertEquals(CommandDetailState.SUCCEEDED, pending.state)
            assertTrue(pending.settlementPending)
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
            assertNull(f.ownership.retainedOwner())
            assertEquals(2, attempts)
            assertEquals(1, settled)
            val collected =
                requireNotNull(
                    CommandResultBrowser.browseSync(f.storage, f.job.binding.turnId, f.job.binding.toolCallId),
                )
            assertEquals(CommandDetailState.SUCCEEDED, collected.state)
            assertFalse(collected.settlementPending)
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
            assertFalse(requireNotNull(DetachedJobObservationStore(f.storage).read(f.job.binding)).settled)
            val reloaded = f.ownership.guard(collector().executor())
            assertTrue(reloaded.execute(f.call) is ToolExecutorResult.Completed)
            assertEquals(1, imports)
            assertEquals(2, settlements)
            assertNull(f.ownership.retainedOwner())
            assertTrue(requireNotNull(DetachedJobObservationStore(f.storage).read(f.job.binding)).settled)
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

    @Test fun realOutputImportHonorsNewDenialAndRetriesTheOriginalTarget() {
        ensureInstalledRuntime(context)
        Fixture(context, output = "scope:app:output/original.txt").use { f ->
            finish(f)
            f.storage.sessionPermissionConfigs.setForSession(
                f.job.binding.sessionId,
                SessionPermissionConfig.custom(mapOf(OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY)),
                1,
            )
            val executor = f.outputCollector()
            assertThrows(IllegalStateException::class.java) { executor.execute(f.call) }
            assertTrue(!f.outputFile.exists())
            assertEquals(f.owner, f.ownership.retainedOwner())
            f.storage.sessionPermissionConfigs.setForSession(
                f.job.binding.sessionId,
                SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                2,
            )
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
            assertEquals("collection-proof", f.outputFile.readText())
            assertNull(f.ownership.retainedOwner())
            f.outputFile.writeText("later user edit")
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
            assertEquals("later user edit", f.outputFile.readText())
        }
    }

    @Test fun disabledOriginalToolCannotApplyDeferredOutput() {
        ensureInstalledRuntime(context)
        Fixture(context, output = "scope:app:output/original.txt").use { f ->
            finish(f)
            f.storage.toolAvailability.set(
                DetachedJobTools.start().origin.canonicalOf(),
                DetachedJobTools.START,
                ToolAvailabilityScope.SESSION,
                f.job.binding.sessionId,
                ToolAvailabilityState.DISABLED,
                1,
            )
            assertThrows(IllegalStateException::class.java) { f.outputCollector().execute(f.call) }
            assertTrue(!f.outputFile.exists())
            assertEquals(f.owner, f.ownership.retainedOwner())
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

    @Test fun missingRecordNeedsRebootAndKeepsOwnershipWhenBudgetSettlementFails() {
        Fixture(context).use { f ->
            var boot = 1
            var status = ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND
            var failBudget = true
            val collector =
                DetachedJobMissingCollection(f.storage, { boot }) {
                    if (failBudget) throw IOException("injected settlement failure")
                }
            val executor =
                f.ownership.guard(
                    f.ownership.controlExecutor(
                        resolve = { f.owner },
                        execute = { call, permit -> collector.collect(f.job.binding, status, call.cancel, permit) },
                    ),
                )
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Failed)
            f.storage.turns.updateState(
                f.storage.turns.resolve(f.job.binding.turnId),
                TurnState.INTERRUPTED,
                0,
                3,
                null,
            )
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Failed)
            assertEquals(f.owner, f.ownership.retainedOwner())
            boot = 2
            status = ProotRuntimeProtocol.REPLY_JOB_UNAVAILABLE
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Failed)
            assertEquals(f.owner, f.ownership.retainedOwner())
            status = ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND
            assertThrows(IOException::class.java) { executor.execute(f.call) }
            assertEquals(f.owner, f.ownership.retainedOwner())
            assertNull(DetachedJobObservationStore(f.storage).read(f.job.binding))
            failBudget = false
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
            assertNull(f.ownership.retainedOwner())
            assertEquals(
                DetachedCommandFacts("UNKNOWN", null, true),
                DetachedJobObservationStore(f.storage).read(f.job.binding),
            )
            assertTrue(executor.execute(f.call) is ToolExecutorResult.Completed)
        }
    }

    @Test fun cancelledMissingCollectionDoesNotConsumeBudgetOrReleaseOwnership() {
        Fixture(context).use { f ->
            val collector = DetachedJobMissingCollection(f.storage, { 2 }) { error("must not settle") }
            val executor =
                f.ownership.guard(
                    f.ownership.controlExecutor(
                        resolve = { f.owner },
                        execute = { _, permit ->
                            collector.collect(
                                f.job.binding,
                                ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND,
                                object : com.helix.tools.framework.CancelSignal {
                                    override fun isCancelled() = true
                                },
                                permit,
                            )
                        },
                    ),
                )
            assertEquals(ToolExecutorResult.Cancelled, executor.execute(f.call))
            assertEquals(f.owner, f.ownership.retainedOwner())
        }
    }

    private class Fixture(
        private val context: HelixApplication,
        script: String = "printf collection-proof > result.txt",
        output: String? = null,
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
                buildJsonObject { if (output != null) put("output", output) }.toString(),
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

        val outputFile get() = File(root, "workspace/output/original.txt")

        fun outputCollector(): com.helix.tools.framework.ToolExecutor {
            val workspace = WorkspaceArtifactStore({ File(root, "workspace").toPath() })
            File(root, "workspace").mkdirs()
            workspace.ensureLayout("app")
            val permissions =
                SessionPermissionService(
                    storage.sessionPermissionConfigs,
                    storage.toolAvailability,
                    workspaceFor = { "app" },
                )
            val output = DetachedJobOutput(storage, workspace, permissions, { "app" }, File(root, "import"))
            val collector = DetachedJobCollection(context, storage, ownership, output::apply, { _, _ -> })
            return ownership.guard(collector.executor())
        }

        override fun close() {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
            job.directory.deleteRecursively()
        }
    }
}
