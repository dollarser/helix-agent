package com.helix.app.proot

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.helix.app.APP_SCOPE_ID
import com.helix.app.application.runtime.ProotInstallationStatus
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
import com.helix.runtime.proot.core.RuntimeInstallationState
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
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
 * Registration and passive inspection never bind. A missing environment is prepared on the
 * first authorized execution; an existing damaged or outdated copy requires user maintenance.
 * Execution authorization, original identity and physical capacity remain independent.
 */
@Suppress("TooManyFunctions") // Tool wiring, execution observation and environment management.
internal object ProotToolModule {
    const val AVAILABLE: Boolean = true

    // The supervisor normalizes its Context to applicationContext in its constructor;
    // this process-lifetime module cannot retain an Activity/Service instance.
    @SuppressLint("StaticFieldLeak")
    private lateinit var supervisor: ProotRuntimeSupervisor
    private lateinit var jobClient: ProotJobClient
    private lateinit var idGenerator: IdGenerator
    private lateinit var userJobActions: DetachedJobUserActions
    private var executionOwnership: com.helix.tools.framework.ExecutionOwnership? = null
    private var foregroundObserver: com.helix.app.chat.AutomaticRuntimeCollection? = null
    private var store: WorkspaceArtifactStore? = null
    private var secretValues: () -> Set<String> = { emptySet() }
    private val jobSeq =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /**
     * Wires passive installation queries and explicit environment/diagnostic entry points.
     * The container uses [registerTools] for full tool wiring; device fixtures can
     * exercise the same application surfaces without initializing a model session.
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
    @Suppress("LongParameterList")
    fun registerTools(
        context: Context,
        registry: ToolRegistry,
        workspaceStore: WorkspaceArtifactStore,
        storage: HelixStorage,
        ownership: com.helix.tools.framework.ExecutionOwnership,
        chat: () -> com.helix.app.chat.ChatService,
        scope: kotlinx.coroutines.CoroutineScope? = null,
        resolveWorkspacePath: (
            com.helix.core.workspace.FileScopePath,
        ) -> File = { error("Workspace resolver unavailable") },
    ) {
        wireForTest(context)
        idGenerator = RandomIdGenerator()
        prepareForegroundRecovery(ownership, scope)
        store = workspaceStore
        // The screening snapshot: the CURRENT secret VALUES of this installation, read
        // on every execution (not cached — a value rotated mid-session is still
        // screened by equality at submit time). This screen is not UID isolation;
        // it runs in the main process before the wire.
        secretValues = { currentSecretValues(storage) }
        val liveWorkspace =
            LinuxLiveWorkspace(
                resolveWorkspacePath,
                listOf("app", "managed").map { File(context.filesDir.canonicalFile, "workspaces/$it") },
            )
        // HXA-209 C5: the session authorization for background jobs — the SAME service,
        // classifier and resolver as the dispatcher's start gate. A new prohibition
        // (a disable, or a rule now DENYing the operation) refuses the launch BEFORE
        // submit; the binding store records the session and the config version that
        // covered this call's approval (ADR sections 4 + 5).
        val prootWorkspace: (String) -> String? = { sessionId -> workspaceFor(storage, sessionId) }
        val sessionPermissions =
            SessionPermissionService(storage.sessionPermissionConfigs, storage.toolAvailability, prootWorkspace)
        val recheck =
            LinuxSessionPermissionRecheck(
                sessionPermissions,
                sessionPermissions,
                SessionToolEffectClassifier(prootWorkspace),
                LinuxRunTool.descriptor(),
            )
        val bindingStore =
            ProotJobBindingStore(storage, sessionPermissions::configFor) {
                DetachedJobBootProof.current(appContext)
            }
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
                ownership = ownership,
                registeredArtifacts = { call -> foregroundArtifacts(storage, call) },
                persistVerifiedResult = { call, record, archive ->
                    persistForegroundResult(storage, call, record, archive)
                },
            )
        registerForeground(registry, executor, liveWorkspace, storage)
        ProotLogTool.register(context, storage, registry, secretValues)
        userJobActions =
            DetachedJobRegistration.register(
                context,
                storage,
                workspaceStore,
                registry,
                ownership,
                chat,
                { availabilityGate() },
                secretValues,
                liveWorkspace::prepareResult,
            )
    }

    private fun foregroundArtifacts(
        storage: HelixStorage,
        call: LinuxRunTool.ParsedLinuxCall,
    ) = ProotResultStore(
        storage,
        File(appContext.filesDir, "workspaces/app"),
        File(appContext.cacheDir, "proot-results"),
    ).produced(requireNotNull(call.turnId), call.toolCallId)

    private fun registerForeground(
        registry: ToolRegistry,
        executor: LinuxRunTool.LinuxExecutor,
        workspace: LinuxLiveWorkspace,
        storage: HelixStorage,
    ) {
        LinuxRunTool.register(registry) { call, cancel ->
            when (val prepared = workspace.prepareResult(call)) {
                is LinuxRunTool.ParsedResult.ParseFailure -> {
                    LinuxRunTool.failed(prepared.detail, "WORKSPACE_UNAVAILABLE")
                }

                is LinuxRunTool.ParsedResult.Ok -> {
                    try {
                        executor.execute(prepared.call, cancel)
                    } finally {
                        observeForegroundExecution(storage)
                    }
                }
            }
        }
    }

    private fun prepareForegroundRecovery(
        ownership: com.helix.tools.framework.ExecutionOwnership,
        scope: kotlinx.coroutines.CoroutineScope?,
    ) {
        executionOwnership = ownership
        foregroundObserver =
            scope?.let {
                com.helix.app.chat.AutomaticRuntimeCollection(
                    kotlinx.coroutines.CoroutineScope(it.coroutineContext + kotlinx.coroutines.Dispatchers.IO),
                )
            }
    }

    private fun persistForegroundResult(
        storage: HelixStorage,
        call: LinuxRunTool.ParsedLinuxCall,
        record: com.helix.runtime.proot.ipc.ProotJobRecord,
        archive: File,
    ) {
        val results =
            ProotResultStore(
                storage,
                File(appContext.filesDir, "workspaces/app"),
                File(appContext.cacheDir, "proot-results"),
            )
        val client =
            com.helix.runtime.proot.client
                .ProotResultClient(supervisor)
        ProotResultCommitter(storage, results, client::acknowledge)
            .commit(requireNotNull(call.turnId), call.toolCallId, record, archive)
    }

    private fun workspaceFor(
        storage: HelixStorage,
        sessionId: String,
    ): String? =
        storage.sessions
            .find(sessionId)
            ?.let { it.directoryRef ?: APP_SCOPE_ID }

    private fun currentSecretValues(storage: HelixStorage): Set<String> {
        val values = mutableSetOf<String>()
        for (alias in storage.secrets.aliases()) {
            runCatching { storage.secrets.get(alias) }.onSuccess { values += it }
        }
        return values
    }

    fun originalDetachedOutput(
        storage: HelixStorage,
        sessionId: String,
        callId: String,
    ): String? {
        val binding = ProotJobBindingStore(storage).resolveDetached(sessionId, callId)
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(binding.turnId, callId))
        check(call.name == DetachedJobTools.START)
        return kotlinx.serialization.json.Json
            .parseToJsonElement(call.argsJson)
            .let { it as kotlinx.serialization.json.JsonObject }["output"]
            ?.jsonPrimitive
            ?.content
    }

    /**
     * Inspect the retained original job. Recovery never prepares an environment or
     * authorizes a replacement execution.
     */
    fun inspectInterruptedJob(
        storage: HelixStorage,
        turnId: String,
        callId: String,
        stop: Boolean,
    ): ProotRecoveryReport {
        recoverForegroundExecution(storage)
        return ProotJobRecovery(storage, jobClient).inspect(turnId, callId, stop)
    }

    /** Observe the retained original execution; never submit or renew a budget. */
    fun observeForegroundExecution(storage: HelixStorage) {
        val host = executionOwnership ?: return
        host.retainedOwners().filter { it.executionId.startsWith(ForegroundProotOwnership.PREFIX) }.forEach { owner ->
            foregroundObserver?.request("${owner.executionId}:${owner.generation}") {
                if (host.isRetained(owner)) recoverForegroundOwner(storage, host, owner)
                if (!host.isRetained(owner)) {
                    com.helix.app.chat.AutomaticRuntimeCollection.Observation.COMPLETE
                } else {
                    com.helix.app.chat.AutomaticRuntimeCollection.Observation.RUNNING
                }
            }
        }
    }

    /** Only a persisted unfinished foreground execution permits this recovery query at startup. */
    fun recoverForegroundExecution(storage: HelixStorage) {
        val host = executionOwnership ?: return
        host.retainedOwners().filter { it.executionId.startsWith(ForegroundProotOwnership.PREFIX) }.forEach { owner ->
            recoverForegroundOwner(storage, host, owner)
        }
    }

    private fun recoverForegroundOwner(
        storage: HelixStorage,
        host: com.helix.tools.framework.ExecutionOwnership,
        owner: com.helix.tools.framework.ExecutionOwnership.Owner,
    ) {
        val binding = ProotJobBindingStore(storage).resolve(owner.generation)
        ForegroundProotOwnership(host).recover(binding, DetachedJobBootProof.current(appContext), jobClient::query)
    }

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
            val detached = detachedCommandFacts(storage, turnId, callId)
            val file =
                ProotResultStore(
                    storage,
                    File(appContext.filesDir, "workspaces/app"),
                    File(appContext.cacheDir, "proot-results"),
                ).readLocal(turnId, callId)
            if (file == null) {
                CommandBrowseFacts(binding, null, false, detached)
            } else {
                CommandBrowseFacts(
                    binding,
                    ProotResultPreview
                        .read(file, File(appContext.cacheDir, "proot-preview"))
                        .copy(acknowledged = null),
                    false,
                    detached,
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
        } catch (error: com.helix.runtime.proot.ipc.ProotIpcException) {
            Log.w("ProotToolModule", "Local Job identity invalid", error)
            CommandBrowseFacts(binding, null, true)
        }
    }

    private fun detachedCommandFacts(
        storage: HelixStorage,
        turnId: String,
        callId: String,
    ): DetachedCommandFacts? {
        if (storage.toolCalls.byTurnAndCallId(turnId, callId)?.name != "code.linux.job.start") return null
        val sessionId = storage.turns.resolve(turnId).sessionId
        val original = ProotJobBindingStore(storage).resolveDetached(sessionId, callId)
        return DetachedJobObservationStore(storage).read(original)
    }

    fun observeCommandLog(binding: CommandJobBindingFacts) = CommandLogReader.observe(binding)

    fun backgroundJobs(storage: HelixStorage): List<BackgroundJobUi> =
        DetachedJobDashboard.read(storage).map { it.copy(canReleaseLaunchWait = userJobActions.isLaunchWaiting(it)) }

    fun performBackgroundJobAction(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
        cancelled: () -> Boolean,
    ): BackgroundJobActionOutcome = userJobActions.perform(job, action, cancelled)

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

            supervisor.installation().state in
                setOf(
                    RuntimeInstallationState.PREPARED,
                    RuntimeInstallationState.NOT_PREPARED,
                )
            -> {
                LinuxRuntimeGate.READY
            }

            else -> {
                LinuxRuntimeGate.NEEDS_MAINTENANCE
            }
        }
    }

    /**
     * The flavor-neutral runtime readiness (HXA-205 readiness view): the LIVE [availabilityGate]
     * mapped to the flavor-neutral [RuntimeReadiness] enum. Bind-free, exactly like
     * [installationStatus] — passive entry of the readiness view reads it without a cold bind.
     */
    fun runtimeReadiness(): RuntimeReadiness =
        when (availabilityGate()) {
            LinuxRuntimeGate.READY -> RuntimeReadiness.READY
            LinuxRuntimeGate.NOT_INSTALLED -> RuntimeReadiness.NOT_INSTALLED
            LinuxRuntimeGate.NEEDS_MAINTENANCE -> RuntimeReadiness.NEEDS_MAINTENANCE
            LinuxRuntimeGate.DISABLED_OR_FORCED_STOPPED -> RuntimeReadiness.DISABLED_OR_FORCED_STOPPED
        }

    /** Explicit installed-copy and connection check, used by diagnostics; never grants task authorization. */
    fun verifyNow(nowEpochMs: Long = System.currentTimeMillis()): ProotRuntimeAvailability =
        supervisor.verify(nowEpochMs)

    /** Opens the explicit maintenance surface; UI owns the localized failure message. */
    fun openEnvironment(): Boolean = supervisor.openRepairActivity() == RepairEntryResult.Opened

    fun openLegalPage(): RepairEntryResult = supervisor.openLegalActivity()

    /** The wire-validated job id: `job_` + 12 lowercase hex (nanoTime + sequence + random mix). */
    private fun nextJobId(): String {
        val mixed = (System.nanoTime() * 31L + jobSeq.incrementAndGet() + 0x517CC1B727220A95L).toUInt()
        return "job_" + mixed.toString(16).padStart(6, '0').takeLast(6) + idGenerator.next().take(6)
    }

    /** Passive, current installation facts. No process is started by opening settings. */
    fun installationStatus(): ProotInstallationStatus =
        when (supervisor.installation().state) {
            RuntimeInstallationState.NOT_PREPARED -> ProotInstallationStatus.NOT_PREPARED
            RuntimeInstallationState.UPDATE_REQUIRED -> ProotInstallationStatus.UPDATE_REQUIRED
            RuntimeInstallationState.DAMAGED -> ProotInstallationStatus.DAMAGED
            RuntimeInstallationState.RESET_REQUIRED -> ProotInstallationStatus.RESET_REQUIRED
            RuntimeInstallationState.PREPARED -> ProotInstallationStatus.PREPARED
        }
}

private lateinit var appContext: Context
