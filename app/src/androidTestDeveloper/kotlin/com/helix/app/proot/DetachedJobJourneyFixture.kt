package com.helix.app.proot

import com.helix.app.HelixApplication
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionConfig
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Real production Dispatcher/Runtime; only the producing model request is a fixture. */
internal class DetachedJobJourneyFixture(
    private val app: HelixApplication,
) : AutoCloseable {
    val container = app.appContainer
    val storage = container.storage
    val id = UUID.randomUUID().toString()
    val output = File(app.filesDir, "workspaces/app/output/$id")
    val job = BackgroundJobUi(id, id, id, "Tasks Job $id", CommandDetailState.SUBMITTED, true)
    private val profile = container.profileStore.profile

    fun start(script: String) {
        ensureInstalledRuntime(app)
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        check(ProotToolModule.verifyNow() is ProotRuntimeAvailability.Verified)
        storage.sessions.create(id, job.title, null, null, System.currentTimeMillis())
        permission(SessionPermissionMode.FULL_ACCESS)
        storage.turns.start(id, id, System.currentTimeMillis())
        val args =
            buildJsonObject {
                put("script", script)
                put("output", "scope:app:output/$id")
                put("leaseSeconds", 60)
            }
        storage.toolCalls.append(id, id, id, DetachedJobTools.START, "1", args.toString(), "RUNNING")
        val outcome =
            container.toolPipeline.dispatcher.dispatch(
                ToolDispatchRequest(
                    toolCallId = id,
                    turnId = id,
                    sessionId = id,
                    toolName = ToolName(DetachedJobTools.START),
                    toolVersion = ToolVersion(1),
                    args = args,
                    mode = AgentMode.ACT,
                    profile = SafetyProfile.ADVANCED,
                    executionTarget = ExecutionTargetType.LOCAL_PROOT,
                    dataOrigin = DataOrigin.WORKSPACE,
                    scope = null,
                    uiToken = "chat:$id",
                ),
            )
        check(outcome is ToolDispatchOutcome.Succeeded) { "START failed: $outcome" }
    }

    fun permission(mode: SessionPermissionMode) {
        storage.sessionPermissionConfigs.setForSession(id, SessionPermissionConfig.of(mode), System.currentTimeMillis())
    }

    fun denyOutput() {
        storage.sessionPermissionConfigs.setForSession(
            id,
            SessionPermissionConfig.custom(mapOf(OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY)),
            System.currentTimeMillis(),
        )
    }

    fun awaitTerminal(): ProotJobRecord {
        val binding = ProotJobBindingStore(storage).resolveDetached(id, id)
        val client = DetachedJobClient(app)
        val until = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            val record = client.query(binding).record
            if (record?.state?.isTerminal == true) return record
            Thread.sleep(100)
        }
        error("Original Job did not terminate")
    }

    override fun close() {
        try {
            if (storage.auditEvents.listByCorrelation(id).none { it.type == "proot.job_prepared" }) return
            val binding = ProotJobBindingStore(storage).resolveDetached(id, id)
            DetachedJobClient(app).cancel(binding)
            awaitTerminal()
            permission(SessionPermissionMode.FULL_ACCESS)
            check(
                ProotToolModule.performBackgroundJobAction(job, BackgroundJobAction.COLLECT) { false } ==
                    BackgroundJobActionOutcome.SETTLED,
            )
        } finally {
            container.profileStore.switchTo(profile)
            storage.sessions.archive(id, System.currentTimeMillis())
            output.deleteRecursively()
        }
    }
}
