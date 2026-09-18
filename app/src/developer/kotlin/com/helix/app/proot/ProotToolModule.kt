package com.helix.app.proot

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.helix.app.APP_SCOPE_ID
import com.helix.app.R
import com.helix.app.approval.SessionPermissionService
import com.helix.app.readiness.RuntimeReadiness
import com.helix.app.tool.SessionToolEffectClassifier
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
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The PRoot capability module (HXA-085): the DEVELOPER flavor's side of the per-variant
 * [ProotToolModule] seam (same FQN as the consumer no-op, exactly like
 * [com.helix.app.profile.AdvancedProfileAvailability]).
 *
 * Owns the cold-bind [ProotRuntimeSupervisor] + [ProotJobClient] and registers the
 * `code.linux.run` tool (L2 CODE_EXECUTION, per-call approval, shared host UID and network permissions under ADR-0049).
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

    // The supervisor normalizes its Context to applicationContext in its constructor;
    // this process-lifetime module cannot retain an Activity/Service instance.
    @SuppressLint("StaticFieldLeak")
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
        appContext = context.applicationContext
        if (this::supervisor.isInitialized) return
        supervisor = ProotRuntimeSupervisor(appContext)
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
        // screened by equality at submit time). This screen is not UID isolation;
        // it runs in the main process before the wire.
        secretValues = {
            val values = mutableSetOf<String>()
            for (alias in storage.secrets.aliases()) {
                runCatching { storage.secrets.get(alias) }.onSuccess { values += it }
            }
            values
        }
        // HXA-209 C5: the session authorization for background jobs — the SAME service,
        // classifier and resolver as the dispatcher's start gate. A new prohibition
        // (a disable, or a rule now DENYing the operation) refuses the launch BEFORE
        // submit; the binding store records the session and the config version that
        // covered this call's approval (ADR sections 4 + 5).
        val prootWorkspace: (String) -> String? = { sessionId ->
            storage.sessions
                .list()
                .firstOrNull { it.id == sessionId }
                ?.let { it.directoryRef ?: APP_SCOPE_ID }
        }
        val sessionPermissions =
            SessionPermissionService(storage.sessionPermissionConfigs, storage.toolAvailability, prootWorkspace)
        val recheck =
            LinuxSessionPermissionRecheck(
                sessionPermissions,
                sessionPermissions,
                SessionToolEffectClassifier(prootWorkspace),
                LinuxRunTool.descriptor(),
            )
        val bindingStore = ProotJobBindingStore(storage, sessionPermissions::configFor)
        val executor =
            LinuxRunTool.ProductionLinuxExecutor(
                client = jobClient,
                gate = { availabilityGate() },
                store = workspaceStore,
                scratchRoot = File(context.filesDir, "proot-jobs"),
                jobIdProvider = { nextJobId() },
                knownSecretValues = secretValues,
                recheckBeforeSubmit = recheck::check,
                beforeSubmit = bindingStore::record,
                persistVerifiedResult = { call, record, archive ->
                    val results =
                        ProotResultStore(
                            storage,
                            File(context.filesDir, "workspaces/app"),
                            File(context.cacheDir, "proot-results"),
                        )
                    val client =
                        com.helix.runtime.proot.client
                            .ProotResultClient(supervisor)
                    ProotResultCommitter(storage, results, client::acknowledge)
                        .commit(requireNotNull(call.turnId), call.toolCallId, record, archive)
                    Unit
                },
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
    fun inspectInterruptedJob(
        storage: HelixStorage,
        turnId: String,
        callId: String,
        stop: Boolean,
    ): ProotRecoveryReport = ProotJobRecovery(storage, jobClient).inspect(turnId, callId, stop)

    fun recoverInterruptedResult(
        storage: HelixStorage,
        turnId: String,
        callId: String,
        localOnly: Boolean,
    ): ProotRecoveredOutput? {
        val archive = ProotResultRecovery.create(appContext, storage).recover(turnId, callId, localOnly) ?: return null
        return ProotResultPreview
            .read(archive.file, File(appContext.cacheDir, "proot-preview"))
            .copy(acknowledged = if (localOnly) null else archive.acknowledged)
    }

    /**
     * HXA-194 read-only browse of one command call's persisted facts (the command details
     * page). NEVER binds the Runtime, submits or acknowledges: the prepared-job binding
     * row is read straight from the audit trail, and the locally persisted archive (when
     * present) is verified in place and previewed. `archiveReadFailed` is true only when
     * a persisted record claims an archive that no longer verifies. The explicit
     * reconciliation stays [recoverInterruptedResult] behind the existing session entry.
     *
     * Any read failure (missing file, corrupt zip, manifest mismatch) must surface as the
     * read-failed projection, never as a crash — the details page is a pure read, so the
     * catch deliberately covers every failure layer of the local read.
     */
    @Suppress("TooGenericExceptionCaught")
    fun browseCommandResult(
        storage: HelixStorage,
        turnId: String,
        callId: String,
    ): CommandBrowseFacts {
        val binding =
            runCatching { ProotJobBindingStore(storage).resolve(callId) }
                .getOrNull()
                ?.let { payload ->
                    CommandJobBindingFacts(
                        payload.getValue("jobId").jsonPrimitive.content,
                        payload.getValue("executionId").jsonPrimitive.content,
                        payload.getValue("inputManifestSha256").jsonPrimitive.content,
                    )
                }
                ?: return CommandBrowseFacts(null, null, false)
        return try {
            val file =
                ProotResultStore(
                    storage,
                    File(appContext.filesDir, "workspaces/app"),
                    File(appContext.cacheDir, "proot-results"),
                ).readLocal(turnId, callId)
            if (file == null) {
                CommandBrowseFacts(binding, null, false)
            } else {
                CommandBrowseFacts(
                    binding,
                    ProotResultPreview
                        .read(file, File(appContext.cacheDir, "proot-preview"))
                        .copy(acknowledged = null),
                    false,
                )
            }
        } catch (error: java.io.IOException) {
            Log.w("ProotToolModule", "Local archive read failed", error)
            CommandBrowseFacts(binding, null, true)
        } catch (error: IllegalStateException) {
            Log.w("ProotToolModule", "Local archive verification failed", error)
            CommandBrowseFacts(binding, null, true)
        } catch (error: IllegalArgumentException) {
            Log.w("ProotToolModule", "Local archive format invalid", error)
            CommandBrowseFacts(binding, null, true)
        }
    }

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
     * The flavor-neutral runtime readiness (HXA-205 readiness view): the LIVE [availabilityGate]
     * mapped to the flavor-neutral [RuntimeReadiness] enum. Bind-free, exactly like
     * [verifyStatusLabel] — passive entry of the readiness view reads it without a cold bind.
     */
    fun runtimeReadiness(): RuntimeReadiness =
        when (availabilityGate()) {
            LinuxRuntimeGate.READY -> RuntimeReadiness.READY
            LinuxRuntimeGate.NOT_INSTALLED -> RuntimeReadiness.NOT_INSTALLED
            LinuxRuntimeGate.NOT_VERIFIED -> RuntimeReadiness.NOT_VERIFIED
            LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED -> RuntimeReadiness.DISABLED_OR_FORCED_STOPPED
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
    fun verifyNowNote(): ProotVerificationNote =
        when (val availability = verifyNow()) {
            is ProotRuntimeAvailability.Verified -> {
                ProotVerificationNote(appContext.getString(R.string.settings_proot_verify_success))
            }

            is ProotRuntimeAvailability.Unavailable -> {
                when (availability.cause) {
                    UnavailableCause.LOCK_MISMATCH -> {
                        ProotVerificationNote(
                            appContext.getString(R.string.settings_proot_verify_mismatch),
                            needsRebaseline = true,
                        )
                    }

                    else -> {
                        ProotVerificationNote(
                            appContext.getString(
                                R.string.settings_proot_verify_failure,
                                availability.cause,
                            ),
                        )
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
    fun removeRuntimeNote(): ProotVerificationNote =
        when (val result = removeRuntime()) {
            RepairEntryResult.Opened -> {
                ProotVerificationNote(appContext.getString(R.string.settings_proot_remove_opened))
            }

            is RepairEntryResult.Unavailable -> {
                ProotVerificationNote(
                    appContext.getString(R.string.settings_proot_remove_unavailable, result.cause),
                )
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
            LinuxRuntimeGate.READY -> {
                appContext.getString(R.string.settings_proot_verified)
            }

            LinuxRuntimeGate.NOT_INSTALLED -> {
                appContext.getString(R.string.settings_proot_not_installed)
            }

            LinuxRuntimeGate.NOT_VERIFIED -> {
                appContext.getString(R.string.settings_proot_not_verified)
            }

            LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED -> {
                appContext.getString(R.string.settings_proot_disabled)
            }
        }

    /** The profile the Advanced section of the settings UI is rendered under (never binds). */
    fun advancedActive(profile: SafetyProfile): Boolean = profile == SafetyProfile.ADVANCED
}

private lateinit var appContext: Context
