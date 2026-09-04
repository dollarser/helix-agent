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
import com.helix.runtime.proot.ipc.UnavailableCause
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
@Suppress("TooManyFunctions") // HXA-085 wiring + HXA-087 legal/removal/re-baseline surfaces
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
     * Standalone supervisor wiring (HXA-087): the user-click surfaces
     * ([openLegalPage]/[removeRuntime]/[rebaseline]/[verifyNow]) only need the
     * supervisor. The container's init path uses [registerTools] (full tool
     * wiring); the instrumented E2E process — which has no container init — uses
     * this to exercise the SAME module surfaces a user click would reach.
     */
    fun wireForTest(context: Context) {
        if (this::supervisor.isInitialized) return
        supervisor = ProotRuntimeSupervisor(context)
        jobClient = ProotJobClient(supervisor)
    }

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
        wireForTest(context)
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

    /**
     * The user-click "验证 Runtime" result note (HXA-087 需更新 detection): runs the
     * zero-Job verification and maps the result to the user-visible note. The
     * `LOCK_MISMATCH` cause is the stable "需更新" state — the companion APK's
     * embedded lock moved away from the persisted anchor (a companion update
     * happened); the UI then offers the explicit "重定基线" action. Blocking —
     * call off the main thread.
     */
    fun verifyNowNote(): String =
        when (val availability = verifyNow()) {
            is ProotRuntimeAvailability.Verified -> {
                "验证通过（基线一致）"
            }

            is ProotRuntimeAvailability.Unavailable -> {
                when (availability.cause) {
                    UnavailableCause.LOCK_MISMATCH -> {
                        "需更新：Runtime 基线与已验证锚点不匹配（Runtime APK 已更新？）。" +
                            "请在「修复 Runtime」中更新，再点「重定基线」并重新验证。"
                    }

                    else -> {
                        "验证失败：${availability.cause}"
                    }
                }
            }
        }

    /**
     * The user-click "删除 Runtime" action (HXA-087 完整删除): clears THIS app's
     * persisted anchor (its own file — the verification claim is void once the
     * runtime is being removed) and opens the companion repair activity carrying
     * the removal consent extra; the companion then shows its own explicit remove
     * button (the in-surface confirmation). The Workspace is in this package's
     * other directories and is never touched — the companion's removal is scoped
     * to its `filesDir/runtime` (ProotRuntimeRemoval). Never throws.
     */
    fun removeRuntime(): RepairEntryResult {
        supervisor.clearAnchorForRebaseline()
        return supervisor.openRepairActivity(removeRuntime = true)
    }

    /**
     * The user-click "删除 Runtime" result note (the [removeRuntime] outcome as
     * user-visible text; the UI never touches the typed result — flavor-neutral
     * settings screen). Blocking — call off the main thread.
     */
    fun removeRuntimeNote(): String =
        when (val result = removeRuntime()) {
            RepairEntryResult.Opened -> {
                "已清除本应用锚点；请在弹出的 Runtime 页面点「删除 Runtime」完成。"
            }

            is RepairEntryResult.Unavailable -> {
                "无法打开 Runtime（${result.cause}）"
            }
        }

    /**
     * The user-click "许可证与来源" action (HXA-087 法律页): opens the companion's
     * offline legal/build-manifest page. User-gated, never throws.
     */
    fun openLegalPage(): RepairEntryResult = supervisor.openLegalActivity()

    /**
     * The EXPLICIT re-baseline action (HXA-087 更新): only reachable from the UI
     * AFTER it has shown the stable "需更新（基线不匹配）" state (a companion APK
     * update moved the embedded lock away from the verified anchor). Clears the
     * anchor so the NEXT user-click "验证 Runtime" re-establishes it against the
     * new lock. Two explicit user actions, never automatic — the anchor's
     * "no silent acceptance of a moved baseline" property is preserved.
     */
    fun rebaseline(): Boolean = supervisor.clearAnchorForRebaseline()

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
