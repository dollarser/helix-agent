package com.helix.app.proot

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.ExecutionTargetType
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.client.ProotResultClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class DetachedJobLaunchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Before fun prepare() {
        ensureInstalledRuntime(context)
    }

    @Test fun acceptanceReturnsWithDurableBindingAndVerifiedOutputRemainsFetchable() {
        Fixture(context).use { fixture ->
            val result = fixture.run() as ToolExecutorResult.Completed
            assertEquals(
                "true",
                result.output.jsonObject
                    .getValue("accepted")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "false",
                result.output.jsonObject
                    .getValue("executionComplete")
                    .jsonPrimitive.content,
            )
            val binding = requireNotNull(fixture.binding)
            assertEquals(binding.executionId, fixture.ownership.retainedOwner()?.executionId)
            assertNull(fixture.ownership.acquire("other-session"))
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            var record = fixture.client.query(binding).record
            while (record?.state?.isTerminal != true && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
                record = fixture.client.query(binding).record
            }
            assertEquals(ProotJobState.SUCCEEDED, requireNotNull(record).state)
            fixture.verifyOutput(record)
            assertTrue(fixture.ownership.retainedOwner() != null)
        }
    }

    @Test fun permissionTighteningDoesNotAllocateOrSubmit() {
        Fixture(context).use { fixture ->
            fixture.deny = true
            assertTrue(fixture.run() is ToolExecutorResult.Failed)
            assertNull(fixture.binding)
            assertNull(fixture.ownership.retainedOwner())
            assertEquals(0, fixture.rejected)
        }
    }

    @Test fun refusedBudgetIsRolledBackWithoutRuntimeSubmission() {
        Fixture(context).use { fixture ->
            fixture.allocated = 999
            assertTrue(fixture.run() is ToolExecutorResult.Failed)
            assertEquals(1, fixture.rejected)
            assertNull(fixture.ownership.retainedOwner())
            assertNull(fixture.client.query(requireNotNull(fixture.binding)).record)
        }
    }

    @Test fun foregroundRefusalReleasesOnlyAfterNoStartAccounting() {
        Fixture(context).use { fixture ->
            shell("appops set ${context.packageName} START_FOREGROUND deny")
            try {
                assertTrue(fixture.run() is ToolExecutorResult.Failed)
                assertEquals(1, fixture.rejected)
                assertNull(fixture.ownership.retainedOwner())
                assertNull(fixture.client.query(requireNotNull(fixture.binding)).record)
            } finally {
                shell("appops set ${context.packageName} START_FOREGROUND allow")
            }
        }
    }

    private class Fixture(
        private val context: HelixApplication,
    ) : AutoCloseable {
        private val name = "launch-${UUID.randomUUID()}"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        private val storage = HelixStorage.open(context, name, File(root, "content"))
        val client = DetachedJobClient(context)
        var binding: DetachedJobBinding? = null
        var deny = false
        var allocated = 60_000L
        var rejected = 0
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
        private val launcher =
            DetachedJobLaunch(
                client,
                { LinuxRuntimeGate.READY },
                WorkspaceArtifactStore({ root.toPath() }),
                File(root, "scratch"),
                ownership,
                { emptySet() },
                { if (deny) ToolExecutorResult.Failed("SESSION_TOOL_DISABLED", sideEffectFree = true) else null },
                { call, spec ->
                    ProotJobBindingStore(storage).recordDetached(call, spec)
                    binding = ProotJobBindingStore(storage).resolveDetached("session", "call")
                    allocated
                },
                { _, _ -> rejected++ },
            )

        init {
            storage.sessions.create("session", "Launch test", null, null, 1)
            storage.turns.start("turn", "session", 2)
            storage.toolCalls.append("stored", "turn", "call", "code.linux.run", "2", "{}", "RUNNING")
        }

        fun run(): ToolExecutorResult =
            ownership.guard(LinuxRunTool.executor(launcher)).execute(
                ExecutableToolCall(
                    "call",
                    "code.linux.run",
                    "2",
                    buildJsonObject { put("script", "sleep 2; printf LAUNCH_OK") },
                    ExecutionTargetType.LOCAL_PROOT,
                    Instant.now().plusSeconds(60),
                    NoCancellation,
                    "session",
                    "turn",
                ),
            )

        fun verifyOutput(record: com.helix.runtime.proot.ipc.ProotJobRecord) {
            val results = ProotResultClient(ProotRuntimeSupervisor(context))
            requireNotNull(results.fetch(record)).use { archive ->
                val zip = File(root, "verified.zip")
                ParcelFileDescriptor.AutoCloseInputStream(archive.descriptor).use { input ->
                    zip.outputStream().use { input.copyTo(it) }
                }
                val extracted = File(root, "extracted")
                assertEquals(record.outputManifestSha256, ZipJobExtractor.extract(zip, extracted).manifestSha256)
                assertEquals("LAUNCH_OK", File(extracted, "stdout.txt").readText())
            }
        }

        override fun close() {
            binding?.let(client::cancel)
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}

private fun shell(command: String) =
    InstrumentationRegistry
        .getInstrumentation()
        .uiAutomation
        .executeShellCommand(command)
        .use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
