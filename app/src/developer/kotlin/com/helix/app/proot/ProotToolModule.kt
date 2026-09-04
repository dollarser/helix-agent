package com.helix.app.proot

import android.content.Context
import com.helix.core.model.IdGenerator
import com.helix.core.model.RandomIdGenerator
import com.helix.core.model.SafetyProfile
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.client.RepairEntryResult
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import java.io.File

/**
 * The PRoot capability module (HXA-085): the DEVELOPER flavor's side of the per-variant
 * [ProotToolModule] seam (same FQN as the consumer no-op, exactly like
 * [com.helix.app.profile.AdvancedProfileAvailability]).
 *
 * Owns the cold-bind [ProotRuntimeSupervisor] + [ProotJobClient] and registers the
 * `code.linux.run` tool (L2 CODE_EXECUTION, per-call approval, offline, no INTERNET —
 * neither the Advanced profile nor a LAN scope can add it; the Runtime APK declares
 * no INTERNET permission).
 *
 * ADR-0007 guarantees kept here:
 * - `registerTools` does NO bind and starts NO process: it wires registries only.
 *   App startup, switching Advanced and passive Registry refresh never bind.
 * - The tool table admission (roadmap HXA-085) is the availability GATE evaluated per
 *   EXECUTION (installed + enabled + the user has ALREADY completed the zero-Job
 *   verification = the persisted anchor). A pre-alive process is not a condition.
 * - "验证 Runtime" (user click) is the ONLY zero-Job bind path; "修复 Runtime" (user
 *   click) is the ONLY repair-activity path. After either, a retry creates a NEW
 *   ToolCall/approval/jobId (nothing is replayed).
 */
internal object ProotToolModule {
    const val AVAILABLE: Boolean = true

    private lateinit var supervisor: ProotRuntimeSupervisor
    private lateinit var jobClient: ProotJobClient
    private lateinit var idGenerator: IdGenerator
    private var store: WorkspaceArtifactStore? = null
    private var secretValues: () -> Set<String> = { emptySet() }
    private val jobSeq =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /**
     * Wires the tool (called once from the container's init, developer flavor only).
     * NO bind, NO process start, NO availability probe: the gate runs per execution.
     */
    fun registerTools(
        context: Context,
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        workspaceStore: WorkspaceArtifactStore,
        storage: HelixStorage,
    ) {
        supervisor = ProotRuntimeSupervisor(context)
        jobClient = ProotJobClient(supervisor)
        idGenerator = RandomIdGenerator()
        store = workspaceStore
        // The screening snapshot: the CURRENT secret VALUES of this installation, read
        // on every execution (not cached — a value rotated mid-session is still
        // screened by equality at submit time). The Runtime never gains SecretStore
        // access; the screen runs in the main process before the wire.
        secretValues = {
            val values = mutableSetOf<String>()
            for (alias in storage.secrets.aliases()) {
                runCatching { storage.secrets.get(alias) }.onSuccess { values += it }
            }
            values
        }
        val executor =
            LinuxRunTool.ProductionLinuxExecutor(
                client = jobClient,
                gate = { availabilityGate() },
                store = workspaceStore,
                scratchRoot = File(context.filesDir, "proot-jobs"),
                jobIdProvider = { nextJobId() },
                knownSecretValues = secretValues,
            )
        LinuxRunTool.register(registry, implementations) { call, isCancelled ->
            executor.execute(call, isCancelled)
        }
    }

    /**
     * The per-execution availability gate (the roadmap's 未安装 / 未验证 / 需更新 /
     * 被禁用/强制停止 distinction). Cheap PackageManager lookups + one anchor file
     * read; NO bind. `READY` additionally requires the persisted anchor (the user has
     * completed the zero-Job verification at least once).
     */
    fun availabilityGate(): LinuxRuntimeGate {
        val cause = supervisor.checkLocalState()
        return when {
            cause != null -> {
                when (cause) {
                    com.helix.runtime.proot.ipc.UnavailableCause.NOT_INSTALLED -> {
                        LinuxRuntimeGate.NOT_INSTALLED
                    }

                    com.helix.runtime.proot.ipc.UnavailableCause.PACKAGE_DISABLED,
                    com.helix.runtime.proot.ipc.UnavailableCause.PACKAGE_FORCED_STOPPED,
                    com.helix.runtime.proot.ipc.UnavailableCause.SIGNATURE_MISMATCH,
                    -> {
                        LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED
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
     * The user-click "验证 Runtime" action (the ONLY zero-Job bind): a fresh cold bind +
     * handshake; on success the anchor is persisted (tool table admission from then on).
     * Blocking — call off the main thread.
     */
    fun verifyNow(nowEpochMs: Long = System.currentTimeMillis()): ProotRuntimeAvailability =
        supervisor.verify(nowEpochMs)

    /** The user-click "修复 Runtime" action (the ONLY repair-activity path). Never throws. */
    fun openRepair(): RepairEntryResult = supervisor.openRepairActivity()

    /** The wire-validated job id: `job_` + 12 lowercase hex (nanoTime + sequence + random mix). */
    private fun nextJobId(): String {
        val mixed = (System.nanoTime() * 31L + jobSeq.incrementAndGet() + 0x517CC1B727220A95L).toUInt()
        return "job_" + mixed.toString(16).padStart(6, '0').takeLast(6) + idGenerator.next().take(6)
    }

    /**
     * The user-click "需更新" detection: an anchor exists but the INSTALLED companion's
     * lock no longer matches it (a companion update happened). Requires a bind — this is
     * only ever invoked from a user-visible surface, never passively.
     */
    fun verifyStatusLabel(): String =
        when (availabilityGate()) {
            LinuxRuntimeGate.READY -> "已验证"
            LinuxRuntimeGate.NOT_INSTALLED -> "未安装"
            LinuxRuntimeGate.NOT_VERIFIED -> "未验证"
            LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED -> "被禁用/强制停止"
        }

    /** The profile the Advanced section of the settings UI is rendered under (never binds). */
    fun advancedActive(profile: SafetyProfile): Boolean = profile == SafetyProfile.ADVANCED
}
