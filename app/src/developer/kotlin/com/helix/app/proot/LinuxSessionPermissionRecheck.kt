package com.helix.app.proot

import com.helix.app.proot.LinuxRunTool.ParsedLinuxCall
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionResolution
import com.helix.core.policy.SessionPermissionResolver
import com.helix.core.policy.SessionPermissionSource
import com.helix.core.policy.ToolAvailabilitySource
import com.helix.core.policy.effectiveAvailability
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolEffectClassifier
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HXA-209 C5 (ADR-PERMISSIONS-001 section 4): the not-yet-launched recheck for a PRoot
 * background job — the only real OS background execution in this build.
 *
 * A job sits between its approval (the proof consumed at the dispatch start gate) and its
 * launch (`ProotJobClient.submit`). In that window the session authorization may tighten
 * (mode switch, rule edit, tool disable). This recheck runs LIVE at submit time, through
 * the SAME seams, classifier and resolver as the dispatch start gate — not a second policy:
 *
 * - a tool DISABLE or a resolver DENY is a NEW PROHIBITION the held proof never covered →
 *   the launch is refused, nothing is submitted, and no journal or binding entry exists;
 * - AUTO_PROCEED is a plain pass;
 * - a tightening to REQUIRES_APPROVAL still PROCEEDS: the call already spent the exact
 *   per-call proof for precisely this command, and section 4 says a valid exact proof is
 *   reusable while it hits no new prohibition (the ASK floor is the approval floor, and it
 *   was already met).
 *
 * The recheck is developer-flavor code on purpose: PRoot is developer-only (the consumer
 * build never registers [com.helix.app.proot.LinuxRunTool]), so its background-job recheck
 * never ships to the consumer lane.
 */
internal class LinuxSessionPermissionRecheck(
    private val permissions: SessionPermissionSource,
    private val availability: ToolAvailabilitySource,
    private val classifier: ToolEffectClassifier,
    private val descriptor: ToolDescriptor,
) {
    /** Null = the held proof still covers this launch; non-null = the job must not be launched. */
    fun check(call: ParsedLinuxCall): ToolExecutorResult? {
        val sessionId =
            call.sessionId
                ?: return LinuxRunTool.failed(
                    "the session context is unavailable — the job was not launched.",
                    "SESSION_CONTEXT_MISSING",
                )
        // 1) The two-state availability, live: a disable after the approval is a new
        //    prohibition, in ANY scope (the service anchors the session's own workspace).
        val states =
            availability.statesFor(descriptor.origin.canonicalOf(), descriptor.name.value, sessionId, null)
        val disabled =
            effectiveAvailability(states.global, states.workspace, states.session) == ToolAvailabilityState.DISABLED
        // 2) The single resolver with the app's classifier — same config, same footprint,
        //    same rm floor as the start gate. Only DENY is a new prohibition. The resolver
        //    is skipped when the tool is disabled (that prohibition wins outright).
        val resolution =
            if (disabled) {
                null
            } else {
                val config = permissions.configFor(sessionId)
                val classification = classifier.classify(requestFor(call, sessionId), descriptor)
                SessionPermissionResolver.resolve(config, classification.footprint, classification.rmCommandHit)
            }
        return when {
            disabled -> {
                LinuxRunTool.failed(
                    "the tool was disabled by the session authorization after this call was approved — " +
                        "the job was not launched; re-enable it and send a new call.",
                    "SESSION_TOOL_DISABLED",
                )
            }

            resolution is SessionPermissionResolution.Denied -> {
                LinuxRunTool.failed(
                    "the session authorization now denies this operation (" +
                        resolution.reasons
                            .joinToString(",") { reason ->
                                reason.effect?.let { "${reason.code.name}:${it.name}" } ?: reason.code.name
                            } +
                        ") — the job was not launched.",
                    "SESSION_OPERATION_DENIED",
                )
            }

            else -> {
                null
            }
        }
    }

    /**
     * The classifier request for the live recheck. The Shell lane of the classifier reads
     * exactly the descriptor name/origin and [ToolDispatchRequest.args] (the exact
     * argv/script being launched) — the other fields are dispatch facts that lane never
     * touches; they carry the CALL'S OWN trusted identity so the request is real, not
     * invented, should any lane grow to read them.
     */
    private fun requestFor(
        call: ParsedLinuxCall,
        sessionId: String,
    ): ToolDispatchRequest {
        val args =
            buildJsonObject {
                when (val command = call.command) {
                    is ProotJobCommand.Argv -> put("argv", JsonArray(command.arguments.map { JsonPrimitive(it) }))
                    is ProotJobCommand.Script -> put("script", JsonPrimitive(command.script))
                }
            }
        return ToolDispatchRequest(
            toolCallId = call.toolCallId,
            turnId = call.turnId.orEmpty(),
            sessionId = sessionId,
            toolName = descriptor.name,
            toolVersion = descriptor.version,
            args = args,
            mode = AgentMode.ACT,
            profile = SafetyProfile.ADVANCED,
            executionTarget = descriptor.executionTarget,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = null,
            uiToken = "proot-recheck",
        )
    }
}
