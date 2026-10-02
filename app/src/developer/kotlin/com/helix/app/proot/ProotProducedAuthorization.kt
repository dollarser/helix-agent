package com.helix.app.proot

import com.helix.app.APP_SCOPE_ID
import com.helix.app.approval.SessionPermissionService
import com.helix.app.tool.SessionToolEffectClassifier
import com.helix.core.model.OperationEffect
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.policy.OperationFootprint
import com.helix.core.policy.SessionPermissionResolution
import com.helix.core.policy.SessionPermissionResolver
import com.helix.core.policy.effectiveAvailability
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.ToolOrigin

/** Rechecks an original authorized Job's deferred files. Does not grant permission or execute code. */
internal class ProotProducedAuthorization(
    private val storage: HelixStorage,
) {
    private val workspaceFor: (String) -> String? = { storage.sessions.resolve(it).directoryRef ?: APP_SCOPE_ID }
    private val permissions =
        SessionPermissionService(storage.sessionPermissionConfigs, storage.toolAvailability, workspaceFor)

    fun checkBeforePublish(
        turnId: String,
        callId: String,
    ) {
        val session = storage.turns.resolve(turnId).sessionId
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(turnId, callId))
        check(call.name in setOf("bash", LinuxRunTool.NAME, DetachedJobTools.START)) { "Not an original Linux Job" }
        val canonical = if (call.name == "bash") LinuxRunTool.NAME else call.name
        for (name in setOf(call.name, canonical)) {
            val states = permissions.statesFor(ToolOrigin.BuiltInOrigin.canonicalOf(), name, session, APP_SCOPE_ID)
            val available = effectiveAvailability(states.global, states.workspace, states.session)
            check(available != ToolAvailabilityState.DISABLED) {
                "Original Job tool is disabled; produced files were not published"
            }
        }
        val path = FileScopePath(APP_SCOPE_ID, "output/jobs")
        val effect =
            if (SessionToolEffectClassifier(workspaceFor).isWorkspacePath(path, session)) {
                OperationEffect.FILE_MUTATION_WORKSPACE
            } else {
                OperationEffect.FILE_MUTATION_EXTERNAL
            }
        // Original execution admitted deferred output. A later DENY invalidates that authority;
        // an ASK does not mint a new grant or require replay of the original command.
        val resolution =
            SessionPermissionResolver.resolve(
                permissions.configFor(session),
                OperationFootprint(setOf(effect)),
                rmCommandHit = false,
            )
        check(resolution !is SessionPermissionResolution.Denied) { "Current session denies produced-file publication" }
    }
}
