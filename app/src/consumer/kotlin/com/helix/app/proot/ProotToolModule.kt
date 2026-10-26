package com.helix.app.proot

import android.content.Context
import com.helix.app.application.runtime.ProotInstallationStatus
import com.helix.app.readiness.RuntimeReadiness
import com.helix.core.model.SafetyProfile
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ToolRegistry

/**
 * The PRoot capability module (HXA-085): the CONSUMER flavor's side of the per-variant
 * [ProotToolModule] seam (same FQN as the developer implementation, exactly like
 * [com.helix.app.profile.AdvancedProfileAvailability]).
 *
 * The consumer channel ships NO PRoot capability (ADR-0013/ADR-0005: the PRoot/CLI
 * companion is a developer/Advanced capability; the consumer Play build has no PRoot
 * client, no `code.linux.run` tool and no repair entry). [registerTools] is a no-op;
 * [AVAILABLE] is false so any shared routing stays variant-neutral.
 */
@Suppress("TooManyFunctions") // flavor-seam parity with the developer module (HXA-085/087 surfaces)
internal object ProotToolModule {
    const val AVAILABLE: Boolean = false

    @Suppress("UnusedParameter")
    fun observeForegroundExecution(storage: HelixStorage) = Unit

    @Suppress("UnusedParameter")
    fun recoverForegroundExecution(storage: HelixStorage) = Unit

    @Suppress("UnusedParameter")
    fun observeCommandLog(binding: CommandJobBindingFacts) = kotlinx.coroutines.flow.emptyFlow<CommandLiveOutput>()

    @Suppress("UnusedParameter")
    fun backgroundJobs(storage: HelixStorage): List<BackgroundJobUi> = emptyList()

    @Suppress("UnusedParameter")
    fun performBackgroundJobAction(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
        cancelled: () -> Boolean,
    ): BackgroundJobActionOutcome = BackgroundJobActionOutcome.FAILED

    val inspectInterruptedJob: (HelixStorage, String, String, Boolean) -> ProotRecoveryReport = { _, _, _, _ ->
        error("PRoot recovery is unavailable in this distribution")
    }

    val recoverInterruptedResult: (HelixStorage, String, String, Boolean) -> ProotRecoveredOutput? = { _, _, _, _ ->
        error("PRoot recovery is unavailable in this distribution")
    }

    /**
     * HXA-194 read-only browse seam (consumer side): the build ships no PRoot capability,
     * so a browse yields empty facts and never throws — the details page can only ever
     * show persisted tool-call facts, and no execution entry is reachable.
     */
    @Suppress("UnusedParameter")
    fun browseCommandResult(
        storage: HelixStorage,
        turnId: String,
        callId: String,
    ): CommandBrowseFacts = CommandBrowseFacts(null, null, false)

    /** No-op seam: the consumer build ships no PRoot capability. */
    @Suppress("UnusedParameter", "LongParameterList")
    fun registerTools(
        context: Context,
        registry: ToolRegistry,
        workspaceStore: WorkspaceArtifactStore,
        storage: HelixStorage,
        ownership: com.helix.tools.framework.ExecutionOwnership,
        chat: () -> com.helix.app.chat.ChatService,
        scope: kotlinx.coroutines.CoroutineScope? = null,
        resolveWorkspacePath: (com.helix.core.workspace.FileScopePath) -> java.io.File = { error("Unavailable") },
    ) {
        // No-op: the consumer build ships no PRoot capability.
    }

    @Suppress("UnusedParameter")
    fun originalDetachedOutput(
        storage: HelixStorage,
        sessionId: String,
        callId: String,
    ): String? = error("Detached Jobs are unavailable in this distribution")

    /** Unreachable in the consumer build (`AVAILABLE == false` guards all call sites). */
    @Suppress("FunctionOnlyReturningConstant")
    fun availabilityGate(): Nothing = error("ProotToolModule is not available in the consumer build (ADR-0005/0013)")

    /** Unreachable in the consumer build. */
    @Suppress("UnusedParameter")
    fun verifyNow(nowEpochMs: Long = System.currentTimeMillis()): Nothing =
        error("ProotToolModule is not available in the consumer build (ADR-0005/0013)")

    @Suppress("FunctionOnlyReturningConstant") // Channel contract: Linux maintenance is unavailable.
    fun openEnvironment(): Boolean = false

    fun installationStatus(): ProotInstallationStatus = ProotInstallationStatus.NOT_AVAILABLE

    fun runtimeReadiness(): RuntimeReadiness = RuntimeReadiness.NOT_AVAILABLE

    /** The profile the Advanced section of the settings UI is rendered under (never binds). */
    fun advancedActive(profile: SafetyProfile): Boolean = profile == SafetyProfile.ADVANCED
}
