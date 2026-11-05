package com.helix.extensions.mobileuse.automation

import com.helix.extensions.mobileuse.config.MobileUseGrant
import com.helix.tools.framework.ExecutableToolCall
import java.util.UUID

/** Remote ordinals never become model tokens. Each token pins original scope, target and backend. */
internal class AutomationPrivilegedSemanticHost(
    private val grantFor: (ExecutableToolCall) -> MobileUseGrant?,
    private val backendName: () -> String,
) {
    private data class Observation(
        val session: String?,
        val turn: String?,
        val scope: String?,
        val grant: MobileUseGrant,
        val backend: AutomationPrivilegedBackend,
        val target: AutomationDisplayTarget,
        val tokens: Map<String, Pair<String, String>>,
    )

    private var observation: Observation? = null
    private val retired = RetiredNodeTokens()

    fun invalidate() {
        observation?.let { retired.remember(it.tokens.keys) }
        observation = null
    }

    // Keep admission, bounded remote read, token binding and publication together.
    @Suppress("ReturnCount", "LongMethod")
    fun snapshot(
        call: ExecutableToolCall,
        backend: AutomationPrivilegedBackend,
    ): AutomationSnapshotResult {
        invalidate()
        val grant = grantFor(call) ?: return refused(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        val allowed = { grantFor(call) == grant }
        val observed =
            backend.device(
                AutomationDeviceRequest(AutomationDeviceOperation.OBSERVE, wholeDisplay = fullDisplayScope(grant)),
                allowed,
            )
        if (!allowed()) return refused(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        val target = observed.target ?: return refused(missingSemanticTargetStatus(observed.status))
        if (!allowed() || !grant.scope.permitsPackage(target.packageName)) {
            return refused(AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED)
        }
        if (!target.semanticAvailable) return refused(AutomationSnapshotStatus.UNSUPPORTED_UI)
        val reply =
            backend.device(
                AutomationDeviceRequest(
                    AutomationDeviceOperation.SNAPSHOT,
                    target,
                    wholeDisplay = fullDisplayScope(grant),
                ),
                allowed,
            )
        if (!allowed()) return refused(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
        val result =
            reply.snapshot ?: return refused(
                if (reply.status == "TARGET_CHANGED") {
                    AutomationSnapshotStatus.TARGET_CHANGED
                } else {
                    AutomationSnapshotStatus.UNSUPPORTED_UI
                },
            )
        val snapshot = result.snapshot ?: return result.copy(backend = backendName())
        if (!target.sameWindow(reply.target) || snapshot.packageName != target.packageName ||
            snapshot.windowId != target.windowId
        ) {
            return refused(AutomationSnapshotStatus.TARGET_CHANGED)
        }
        val issued =
            snapshot.nodes.filter { it.token.isNotEmpty() }.associate { node ->
                node.token to (
                    "p" +
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(31)
                )
            }
        observation =
            Observation(
                call.sessionId,
                call.turnId,
                call.authorizationScopeRef,
                grant,
                backend,
                target,
                snapshot.nodes.filter { it.token.isNotEmpty() }.associate {
                    requireNotNull(issued[it.token]) to (it.token to it.privilegedFingerprint())
                },
            )
        return result.copy(
            backend = backendName(),
            snapshot =
                snapshot.copy(
                    nodes =
                        snapshot.nodes.map {
                            it.copy(token = issued[it.token].orEmpty(), parentToken = issued[it.parentToken])
                        },
                ),
        )
    }

    @Suppress("ReturnCount")
    fun action(
        call: ExecutableToolCall,
        request: AutomationNodeActionRequest,
    ): AutomationActionResult {
        val observed = observation ?: return actionResult(AutomationActionStatus.STALE_TOKEN)
        invalidate()
        if (AutomationDeviceOperation.NODE_ACTION !in observed.backend.deviceOperations) {
            return actionResult(AutomationActionStatus.ACTION_NOT_DISPATCHED)
        }
        val token =
            observed.tokens[request.token] ?: return actionResult(
                if (request.token in retired) {
                    AutomationActionStatus.STALE_TOKEN
                } else {
                    AutomationActionStatus.TOKEN_UNKNOWN
                },
            )
        val allowed = {
            call.sessionId == observed.session && call.turnId == observed.turn &&
                call.authorizationScopeRef == observed.scope &&
                grantFor(call) == observed.grant && observed.backend.state() == AutomationBackendState.READY
        }
        if (!allowed()) return actionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
        val reply =
            observed.backend.device(
                AutomationDeviceRequest(
                    AutomationDeviceOperation.NODE_ACTION,
                    observed.target,
                    wholeDisplay =
                        observed.grant.scope.allApplications &&
                            observed.grant.scope.deniedPackages
                                .isEmpty(),
                    nodeAction = request.copy(token = token.first),
                    nodeFingerprint = token.second,
                ),
                allowed,
            )
        if (!allowed()) return actionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        return reply.action ?: actionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
    }

    private fun refused(status: AutomationSnapshotStatus) = AutomationSnapshotResult(status, backend = backendName())

    private fun actionResult(status: AutomationActionStatus) = AutomationActionResult(status)
}

private fun fullDisplayScope(grant: MobileUseGrant) =
    grant.scope.allApplications && grant.scope.deniedPackages.isEmpty()

private fun missingSemanticTargetStatus(status: String) =
    if (status ==
        "TARGET_UNAVAILABLE"
    ) {
        AutomationSnapshotStatus.TARGET_UNAVAILABLE
    } else {
        AutomationSnapshotStatus.UNSUPPORTED_UI
    }
