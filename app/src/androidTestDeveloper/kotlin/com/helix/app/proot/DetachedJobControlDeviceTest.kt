package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.ExecutionTargetType
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.DetachedJobClient
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class DetachedJobControlDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun boundControlQueriesAndCancelsWithoutReleasingUnsettledEffects() {
        ensureInstalledRuntime(context)
        Fixture(context).use { fixture ->
            val client = DetachedJobClient(context)
            try {
                assertTrue(fixture.job.submit(client).accepted)
                val query = fixture.ownership.guard(fixture.control.executor(stop = false))
                val first = query.execute(fixture.call) as ToolExecutorResult.Completed
                assertEquals(
                    fixture.job.binding.jobId,
                    first.output.jsonObject
                        .getValue("jobId")
                        .jsonPrimitive.content,
                )
                assertNull(fixture.ownership.acquire("foreign-writer"))
                val cancel = fixture.ownership.guard(fixture.control.executor(stop = true))
                assertTrue(cancel.execute(fixture.call) is ToolExecutorResult.Completed)
                val until = android.os.SystemClock.elapsedRealtime() + 15_000
                var state = ""
                while (android.os.SystemClock.elapsedRealtime() < until && state != "CANCELLED") {
                    val report = query.execute(fixture.call) as ToolExecutorResult.Completed
                    state =
                        report.output.jsonObject
                            .getValue("state")
                            .jsonPrimitive.content
                    Thread.sleep(100)
                }
                assertEquals("CANCELLED", state)
                assertEquals(fixture.owner, fixture.ownership.retainedOwner())
                assertNull(fixture.ownership.acquire("writer-before-import"))
            } finally {
                client.cancel(fixture.job.binding)
            }
        }
    }

    @Test fun foreignSessionAndUnknownOriginalCallFailBeforeRuntimeAccess() {
        Fixture(context).use { fixture ->
            val query = fixture.ownership.guard(fixture.control.executor(stop = false))
            assertThrows(IllegalStateException::class.java) {
                query.execute(fixture.call.copy(sessionId = "foreign-session"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                query.execute(fixture.call.copy(args = buildJsonObject { put("originalCallId", "missing") }))
            }
            assertEquals(fixture.owner, fixture.ownership.retainedOwner())
            requireNotNull(fixture.ownership.acquireReconciliation(fixture.owner)).close()
        }
    }

    private class Fixture(
        private val context: HelixApplication,
    ) : AutoCloseable {
        val job = DetachedJobFixture(context, "sleep 60")
        private val name = "bound-control-${UUID.randomUUID()}"
        private val root = File(context.cacheDir, name)
        private val storage = HelixStorage.open(context, name, File(root, "content"))
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
        val control = DetachedJobControl.create(context, storage, ownership)
        val call =
            ExecutableToolCall(
                "control-call",
                "code.linux.job.status",
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
                "code.linux.run",
                "2",
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
