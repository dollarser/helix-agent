@file:Suppress(
    "LongMethod", // one full tool→job→import scenario
    "TooManyFunctions", // the scenario list is the test class's purpose
)

package com.helix.app.proot

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * HXA-085 device acceptance: the `code.linux.run` tool end-to-end from the MAIN app
 * process — descriptor gate (policy/registration), the production executor running a
 * REAL job through the separately signed PRoot Runtime (cold bind, re-handshake per
 * execution, NO replay), the hash-verified output archive, the WORKSPACE IMPORT of the
 * result file (the Runtime never writes the Workspace; the main app imports it), the
 * environment screen at the boundary, and the STANDARD-profile policy denial.
 *
 * Warm-up requirement is the same as the HXA-084 E2E: the companion must be installed
 * and NOT force-stopped. The zero-Job verification (persisting the anchor the gate
 * requires) is performed here BY A TEST ACTION through the supervisor — on a real
 * device the user performs the same action from the settings UI ("验证 Runtime").
 */
@RunWith(AndroidJUnit4::class)
class LinuxRunToolE2eDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val supervisor = ProotRuntimeSupervisor(context)
    private val jobClient = ProotJobClient(supervisor)
    private val counter = AtomicLong(0)

    private val scratch = mutableListOf<File>()

    @Before
    fun warm() {
        assumeTrue(
            "companion not installed — install runtime/proot-app/.../proot-app-debug.apk",
            companionInstalled(context),
        )
        assumeTrue(
            "companion is force-stopped (fresh install?) — warm it via scripts/accept-hxa-083-lifecycle.sh",
            !probeStoppedState(context),
        )
        // The tool's gate requires the persisted anchor (the user completed the zero-Job
        // verification). Perform the SAME user action the settings button performs (one
        // zero-Job bind, no job submitted) so the gate is READY for the scenarios below.
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
    }

    // ------------------------------------------------------------------ descriptor + policy

    @Test
    fun theDescriptorIsL2CodeExecutionOnLocalProot() {
        val d = LinuxRunTool.descriptor()
        assertEquals("code.linux.run", d.name.value)
        assertEquals(com.helix.core.model.ToolOperationClass.CODE_EXECUTION, d.operationClass)
        assertEquals(com.helix.core.model.RiskLevel.L2, d.baseRisk)
        assertEquals(ExecutionTargetType.LOCAL_PROOT, d.executionTarget)
    }

    @Test
    fun theStandardProfilePolicyDeniesTheProotTarget() {
        // ADR-0005/0012: LOCAL_PROOT requires ADVANCED; the STANDARD profile must be
        // denied BEFORE any approval or execution, at the Policy Engine boundary.
        val d = LinuxRunTool.descriptor()
        val input =
            com.helix.core.policy.PolicyInput(
                baseRisk = d.baseRisk,
                operationClass = d.operationClass,
                mode = com.helix.core.model.AgentMode.ACT,
                profile = com.helix.core.model.SafetyProfile.STANDARD,
                source = com.helix.core.policy.ToolCallSource.BuiltIn,
                executionTarget = d.executionTarget,
                dataOrigin = com.helix.core.policy.DataOrigin.WORKSPACE,
                scope = null,
            )
        val evaluation =
            com.helix.core.policy
                .PolicyEngine(
                    com.helix.core.model
                        .SystemClock(),
                ).evaluate(input)
        val deny = evaluation.decision
        assertTrue("STANDARD must not run PRoot jobs: $deny", deny is com.helix.core.policy.PolicyDecision.Deny)
        assertEquals(
            "the denial must name the advanced-only target",
            com.helix.core.policy.PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED,
            (deny as com.helix.core.policy.PolicyDecision.Deny).code,
        )
    }

    // ------------------------------------------------------------------ the real job through the tool

    @Test
    fun anApprovedLinuxCallRunsTheJobAndImportsTheVerifiedResult() {
        runOnWorker(timeoutMs = 180_000L) {
            val store = e2eWorkspaceStore()
            // 1) Stage an input file THROUGH THE STORE (the only read path the tool has).
            //    The seed is an output-region artifact (its region is where the store
            //    created it — the tool reads through the store, never by region).
            val seedParent = File(context.filesDir, "output")
            seedParent.mkdirs()
            val inputRef = FileScopePath("app", "output/e2e-linux-seed.txt")
            store.writeArtifact(inputRef, "SEED-FROM-MAIN-APP\n".toByteArray(), WorkspaceLayout.OUTPUT)
            val outputRef = FileScopePath("app", "output/e2e-linux-result.txt")
            // 2) The EXACT arguments the approval would bind.
            val args =
                buildJsonObject {
                    put(
                        "argv",
                        buildJsonArray {
                            add(JsonPrimitive("/bin/sh"))
                            add(JsonPrimitive("-c"))
                            add(
                                JsonPrimitive(
                                    "cat /workspace/e2e-linux-seed.txt > result.txt" +
                                        " && echo TOOL_OUT && echo TOOL_ERR >&2",
                                ),
                            )
                        },
                    )
                    put(
                        "files",
                        buildJsonArray { add(JsonPrimitive(inputRef.toModelReference())) },
                    )
                    put("output", JsonPrimitive(outputRef.toModelReference()))
                    put("timeoutSeconds", JsonPrimitive(60))
                }
            val call =
                ExecutableToolCall(
                    toolCallId = "tc-linux-e2e-" + nextId(),
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
            val executor = LinuxRunTool.executor(productionExecutor(store))
            val result = executor.execute(call)

            // 3) SUCCEEDED with the captured streams + the imported result.
            val completed =
                result as? ToolExecutorResult.Completed
                    ?: error("the job must settle SUCCEEDED, got: $result")
            val out = completed.output.jsonObject
            assertEquals("SUCCEEDED", out["state"]!!.jsonPrimitive.content)
            assertEquals("0", out["exitCode"]!!.jsonPrimitive.content)
            assertTrue(out["stdout"]!!.jsonPrimitive.content.contains("TOOL_OUT"))
            assertTrue(out["stderr"]!!.jsonPrimitive.content.contains("TOOL_ERR"))
            assertEquals("true", out["outputImported"]!!.jsonPrimitive.content)
            // 4) The import landed in the WORKSPACE (main app side), hash-matched.
            val imported = store.readAll(outputRef)
            assertEquals("SEED-FROM-MAIN-APP\n", String(imported, Charsets.UTF_8))
            assertEquals(
                sha256(imported),
                out["outputSha256"]!!.jsonPrimitive.content,
            )
            // 5) The audit detail carries the job ids (reconcilable by job id, never replayed).
            val audit = completed.auditDetail!!.jsonObject
            assertTrue(audit["jobId"]!!.jsonPrimitive.content.startsWith("job_"))
            Unit
        }
    }

    @Test
    fun theEnvironmentScreenRefusesSecretValuesAtTheToolBoundary() {
        runOnWorker(timeoutMs = 60_000L) {
            val store = e2eWorkspaceStore()
            // A value the SecretStore knows: the screen refuses it even under a harmless name.
            val args =
                buildJsonObject {
                    put("argv", buildJsonArray { add(JsonPrimitive("true")) })
                    put(
                        "environment",
                        buildJsonObject { put("HARMLESS_NAME", JsonPrimitive("e2e-known-secret-value-12345")) },
                    )
                }
            val call =
                ExecutableToolCall(
                    toolCallId = "tc-linux-env-" + nextId(),
                    toolName = LinuxRunTool.NAME,
                    toolVersion = "1",
                    args = args,
                    executionTarget = ExecutionTargetType.LOCAL_PROOT,
                    deadline = Instant.now().plusSeconds(60),
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled(): Boolean = false
                        },
                )
            val executor =
                LinuxRunTool.executor(
                    productionExecutor(store, knownSecretValues = setOf("e2e-known-secret-value-12345")),
                )
            val failed = executor.execute(call) as ToolExecutorResult.Failed
            assertTrue(
                "the screened environment must be refused before the wire: $failed",
                failed.detail.contains("environment refused"),
            )
            Unit
        }
    }

    @Test
    fun aFailedCommandSettlesWithTheStableExitCodeFailure() {
        runOnWorker(timeoutMs = 120_000L) {
            val store = e2eWorkspaceStore()
            val args =
                buildJsonObject {
                    put(
                        "argv",
                        buildJsonArray {
                            add(JsonPrimitive("/bin/sh"))
                            add(JsonPrimitive("-c"))
                            add(JsonPrimitive("exit 3"))
                        },
                    )
                    put("timeoutSeconds", JsonPrimitive(60))
                }
            val call =
                ExecutableToolCall(
                    toolCallId = "tc-linux-fail-" + nextId(),
                    toolName = LinuxRunTool.NAME,
                    toolVersion = "1",
                    args = args,
                    executionTarget = ExecutionTargetType.LOCAL_PROOT,
                    deadline = Instant.now().plusSeconds(110),
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled(): Boolean = false
                        },
                )
            val executor = LinuxRunTool.executor(productionExecutor(store))
            val failed = executor.execute(call) as ToolExecutorResult.Failed
            assertTrue(
                "a non-zero exit must settle as a stable JOB_FAILED failure: $failed",
                failed.detail.contains("FAILED"),
            )
            Unit
        }
    }

    // ------------------------------------------------------------------ wiring helpers

    /**
     * The production executor wired EXACTLY like [ProotToolModule.registerTools] (minus the
     * real SecretStore — the scenarios pass the value set explicitly).
     */
    private fun productionExecutor(
        store: WorkspaceArtifactStore,
        knownSecretValues: Set<String> = emptySet(),
    ): LinuxRunTool.LinuxExecutor {
        val scratchRoot = File(context.filesDir, "proot-jobs-e2e-" + nextId())
        scratch += scratchRoot
        return LinuxRunTool.ProductionLinuxExecutor(
            client = jobClient,
            gate = { supervisorGate() },
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
            knownSecretValues = { knownSecretValues },
        )
    }

    /** The gate the module uses: local state + the persisted anchor (no bind). */
    private fun supervisorGate(): LinuxRuntimeGate {
        val cause = supervisor.checkLocalState()
        return when {
            cause != null -> {
                when (cause) {
                    com.helix.runtime.proot.ipc.UnavailableCause.NOT_INSTALLED -> {
                        LinuxRuntimeGate.NOT_INSTALLED
                    }

                    else -> {
                        LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED
                    }
                }
            }

            supervisor.anchorPresent() -> {
                LinuxRuntimeGate.READY
            }

            else -> {
                LinuxRuntimeGate.NOT_VERIFIED
            }
        }
    }

    /**
     * The E2E workspace store, wired like the app container for the `app` scope: the root is
     * the target app's real workspace root (filesDir). The E2E uses app-scope references so
     * the import lands where the production store reads.
     */
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

    // ------------------------------------------------------------------ infra

    private fun nextId(): String = counter.incrementAndGet().toString()

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b) }

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
