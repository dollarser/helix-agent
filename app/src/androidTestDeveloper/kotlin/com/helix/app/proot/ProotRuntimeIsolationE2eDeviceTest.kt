package com.helix.app.proot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HXA-086 isolation from the MAIN-APP side (the companion-side
 * `ProotIsolationDeviceTest` covers the same boundary for the companion's
 * own app-data + shared storage + network; this class adds the piece only
 * the main app can prove): the guest of a real `code.linux.run` execution
 * (the production executor, the same wiring as [ProotToolModule.registerTools])
 * can neither READ nor WRITE the main app's dataDir — the path is simply
 * outside the guest's visible universe (chroot + the runner's binds), and
 * the marker file stays intact.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // test class: 2 scenarios + the executor/gate/store wiring helpers
class ProotRuntimeIsolationE2eDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val supervisor = ProotRuntimeSupervisor(context)
    private val jobClient = ProotJobClient(supervisor)
    private val counter = AtomicLong(0)
    private val scratch = mutableListOf<File>()

    @Before
    fun warm() {
        org.junit.Assume.assumeTrue(
            "companion not installed — install runtime/proot-app/.../proot-app-debug.apk",
            companionInstalled(context),
        )
        org.junit.Assume.assumeTrue(
            "companion is force-stopped (fresh install?) — warm it via scripts/accept-hxa-083-lifecycle.sh",
            !probeStoppedState(context),
        )
        // The user's one-time verification action (the settings button): a
        // zero-Job bind that persists the anchor the tool gate requires.
        runOnWorker(timeoutMs = 90_000L) {
            val result = supervisor.verify(System.currentTimeMillis())
            assertTrue(
                "the zero-Job verification must succeed on a warm companion: $result",
                result is com.helix.runtime.proot.ipc.ProotRuntimeAvailability.Verified,
            )
            Unit
        }
    }

    @After
    fun cleanScratch() {
        scratch.forEach { it.deleteRecursively() }
        scratch.clear()
        ranMarker?.delete()
        ranMarker = null
    }

    @Test
    fun theGuestCannotReadTheMainAppDataDir() {
        runOnWorker(timeoutMs = 180_000L) {
            val store = e2eWorkspaceStore()
            markRan("e2e-iso-ran-read")
            // The marker lives in the MAIN APP's own filesDir (its dataDir).
            val marker =
                File(context.filesDir, "e2e-086-mainapp-marker.txt").apply {
                    writeText("MAIN-APP-DATA-MUST-STAY")
                }
            try {
                val args =
                    buildJsonObject {
                        put(
                            "argv",
                            buildJsonArray {
                                add(JsonPrimitive("/bin/sh"))
                                add(JsonPrimitive("-c"))
                                add(
                                    JsonPrimitive(
                                        "cat " + marker.absolutePath + " || echo READ-FAILED",
                                    ),
                                )
                            },
                        )
                        put("timeoutSeconds", JsonPrimitive(60))
                    }
                val call = linuxCall("tc-iso-read-", args)
                val completed =
                    LinuxRunTool.executor(productionExecutor(store)).execute(call)
                        as ToolExecutorResult.Completed
                // The sh command keeps exit 0 either way: the assertion is on
                // WHAT the guest saw — ENOENT, never the marker content.
                assertEquals(
                    "0",
                    completed.output.jsonObject["exitCode"]!!
                        .jsonPrimitive.content,
                )
                val stdout =
                    completed.output.jsonObject["stdout"]!!
                        .jsonPrimitive.content
                assertTrue("the guest must NOT see the main app's dataDir: $stdout", "READ-FAILED" in stdout)
                assertFalse(
                    "the marker content must never reach the guest: $stdout",
                    "MAIN-APP-DATA-MUST-STAY" in stdout,
                )
            } finally {
                marker.delete()
            }
            Unit
        }
    }

    @Test
    fun theGuestCannotWriteToTheMainAppDataDir() {
        runOnWorker(timeoutMs = 180_000L) {
            val store = e2eWorkspaceStore()
            markRan("e2e-iso-ran-write")
            val target = File(context.filesDir, "e2e-086-mainapp-written.txt")
            if (target.exists()) target.delete()
            try {
                val args =
                    buildJsonObject {
                        put(
                            "argv",
                            buildJsonArray {
                                add(JsonPrimitive("/bin/sh"))
                                add(JsonPrimitive("-c"))
                                add(
                                    JsonPrimitive(
                                        "touch " + target.absolutePath + " 2>/dev/null || echo WRITE-FAILED",
                                    ),
                                )
                            },
                        )
                        put("timeoutSeconds", JsonPrimitive(60))
                    }
                val call = linuxCall("tc-iso-write-", args)
                val completed =
                    LinuxRunTool.executor(productionExecutor(store)).execute(call)
                        as ToolExecutorResult.Completed
                assertEquals(
                    "0",
                    completed.output.jsonObject["exitCode"]!!
                        .jsonPrimitive.content,
                )
                val stdout =
                    completed.output.jsonObject["stdout"]!!
                        .jsonPrimitive.content
                assertTrue(
                    "the guest must NOT be able to write into the main app's dataDir: $stdout",
                    "WRITE-FAILED" in stdout,
                )
                assertFalse("no file may have appeared in the main app's dataDir", target.exists())
            } finally {
                if (target.exists()) target.delete()
            }
            Unit
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun linuxCall(
        prefix: String,
        args: kotlinx.serialization.json.JsonObject,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = prefix + nextId(),
            toolName = LinuxRunTool.NAME,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_PROOT,
            deadline = Instant.now().plusSeconds(170),
            cancel =
                object : CancelSignal {
                    override fun isCancelled(): Boolean = false
                },
        )

    private fun productionExecutor(store: WorkspaceArtifactStore): LinuxRunTool.LinuxExecutor {
        val scratchRoot = File(context.filesDir, "proot-iso-e2e-" + nextId())
        scratch += scratchRoot
        return LinuxRunTool.ProductionLinuxExecutor(
            client = jobClient,
            gate = { LinuxRuntimeGate.READY },
            store = store,
            scratchRoot = scratchRoot,
            jobIdProvider = {
                "job_" +
                    System
                        .nanoTime()
                        .toUInt()
                        .toString(16)
                        .padStart(6, '0')
                        .takeLast(6) +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(6)
                        .lowercase()
            },
            knownSecretValues = { emptySet() },
        )
    }

    private fun e2eWorkspaceStore(): WorkspaceArtifactStore {
        val root = context.filesDir
        val rootPath =
            java.nio.file.Paths
                .get(root.absolutePath)
        Files.createDirectories(rootPath)
        val resolver = { sid: String ->
            require(sid == "app") { "scope $sid unavailable in the E2E" }
            rootPath
        }
        return WorkspaceArtifactStore(resolver)
    }

    /** Fresh host-side marker (test process filesDir): proof the test body ran. */
    private var ranMarker: File? = null

    private fun markRan(tag: String) {
        val m = File(context.filesDir, tag)
        m.writeText("ran at " + System.currentTimeMillis())
        ranMarker = m
    }

    private fun nextId(): String = counter.incrementAndGet().toString()

    private fun runOnWorker(
        timeoutMs: Long = 120_000L,
        block: () -> Unit,
    ) {
        val future = CompletableFuture.runAsync { block() }
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    @Suppress("SwallowedException") // a missing companion package is the expected absent state
    private fun companionAppInfo(ctx: Context): ApplicationInfo? =
        try {
            ctx.packageManager
                .getPackageInfo(
                    com.helix.runtime.proot.ipc.ProotRuntimeProtocol.RUNTIME_PACKAGE,
                    0,
                ).applicationInfo
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun companionInstalled(ctx: Context): Boolean = companionAppInfo(ctx) != null

    private fun probeStoppedState(ctx: Context): Boolean {
        val appInfo = companionAppInfo(ctx) ?: return false
        return appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
    }
}
