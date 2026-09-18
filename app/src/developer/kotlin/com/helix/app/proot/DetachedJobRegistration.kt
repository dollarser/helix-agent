package com.helix.app.proot

import android.content.Context
import com.helix.app.APP_SCOPE_ID
import com.helix.app.approval.SessionPermissionService
import com.helix.app.chat.ChatService
import com.helix.app.tool.SessionToolEffectClassifier
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import java.io.File

/** Cold wiring only. Runtime and Goal calls happen inside an authorized executor, never during registration. */
internal object DetachedJobRegistration {
    @Suppress("LongParameterList") // One composition boundary reuses the existing app-owned services.
    fun register(
        context: Context,
        storage: HelixStorage,
        workspace: WorkspaceArtifactStore,
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        ownership: ExecutionOwnership,
        chat: () -> ChatService,
        gate: () -> LinuxRuntimeGate,
        secrets: () -> Set<String>,
    ) {
        val workspaceFor: (String) -> String? = { session ->
            storage.sessions
                .list()
                .firstOrNull { it.id == session }
                ?.let { it.directoryRef ?: APP_SCOPE_ID }
        }
        val permissions =
            SessionPermissionService(storage.sessionPermissionConfigs, storage.toolAvailability, workspaceFor)
        val bindings = ProotJobBindingStore(storage, permissions::configFor)
        val recheck =
            LinuxSessionPermissionRecheck(
                permissions,
                permissions,
                SessionToolEffectClassifier(workspaceFor),
                DetachedJobTools.start(),
            )
        val launcher = launcher(context, workspace, ownership, chat, gate, secrets, bindings, recheck)
        val output =
            DetachedJobOutput(storage, workspace, permissions, workspaceFor, File(context.cacheDir, "proot-import"))
        val collection =
            DetachedJobCollection(context, storage, ownership, output::apply) { binding, record ->
                chat().settleDetachedJobBudget(
                    binding.sessionId,
                    binding.turnId,
                    binding.executionId,
                    record.terminalElapsedMs,
                )
            }
        val control = DetachedJobControl.create(context, storage, ownership)
        val executors =
            listOf(
                DetachedJobTools.start() to start(launcher),
                DetachedJobTools.control(false) to control.executor(false),
                DetachedJobTools.control(true) to control.executor(true),
                DetachedJobTools.collect() to collection.executor(),
            )
        for ((descriptor, executor) in executors) {
            registry.register(descriptor)
            implementations.register(descriptor, executor)
        }
    }

    @Suppress("LongParameterList") // Shares the same composition inputs without a second service container.
    private fun launcher(
        context: Context,
        workspace: WorkspaceArtifactStore,
        ownership: ExecutionOwnership,
        chat: () -> ChatService,
        gate: () -> LinuxRuntimeGate,
        secrets: () -> Set<String>,
        bindings: ProotJobBindingStore,
        recheck: LinuxSessionPermissionRecheck,
    ): DetachedJobLaunch =
        DetachedJobLaunch(
            DetachedJobClient(context),
            gate,
            workspace,
            File(context.filesDir, "proot-jobs"),
            ownership,
            secrets,
            recheck::check,
            prepare = { call, spec ->
                bindings.recordDetached(call, spec)
                chat().prepareDetachedJobBudget(
                    requireNotNull(call.sessionId),
                    requireNotNull(call.turnId),
                    spec.executionId,
                    spec.deadlineMs,
                )
            },
            reject = { call, spec ->
                chat().rejectDetachedJobBudget(
                    requireNotNull(call.sessionId),
                    requireNotNull(call.turnId),
                    spec.executionId,
                )
            },
        )

    private fun start(launcher: DetachedJobLaunch): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                when (val parsed = DetachedJobTools.parsed(call)) {
                    is LinuxRunTool.ParsedResult.ParseFailure -> LinuxRunTool.failed(parsed.detail)
                    is LinuxRunTool.ParsedResult.Ok -> launcher.execute(parsed.call) { call.cancel.isCancelled() }
                }
        }
}
