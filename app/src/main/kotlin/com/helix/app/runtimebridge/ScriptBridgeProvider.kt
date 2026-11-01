package com.helix.app.runtimebridge

import android.content.Context
import com.helix.core.model.CancelSignal
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.HostToolBridge
import com.helix.tools.framework.HostToolBridgeProvider
import kotlinx.serialization.json.JsonObject

/** The host owns identity and revocation; no session, mode, scope or backend comes from RPC data. */
internal class ScriptBridgeProvider(
    context: Context,
    private val storage: HelixStorage,
    private val dispatch: (ExecutableToolCall, String, String, JsonObject, CancelSignal) -> ToolDispatchOutcome,
    private val jobPeer: (String, Int, Int) -> Boolean,
    private val jobActive: (String) -> Boolean,
) : HostToolBridgeProvider {
    private val settings = HostToolBridgeSettings(context.applicationContext)

    override fun open(call: ExecutableToolCall): HostToolBridge {
        check(settings.enabled && originalActive(call, false)) { "HOST_TOOL_BRIDGE_DISABLED_OR_INACTIVE" }
        val revision = settings.revision
        return ScriptBridgeSession(
            call,
            active = { continuing -> settings.allows(revision) && originalActive(call, continuing) },
            dispatch = dispatch,
            jobPeer = jobPeer,
            jobActive = jobActive,
        )
    }

    @Suppress("ReturnCount") // Missing durable identity revokes this capability.
    private fun originalActive(call: ExecutableToolCall, continuing: Boolean): Boolean {
        val session = call.sessionId ?: return false
        val turn = call.turnId?.let(storage.turns::find) ?: return false
        val state = TurnState.entries.singleOrNull { it.name == turn.state } ?: return false
        val parent = storage.toolCalls.byTurnAndCallId(turn.id, call.toolCallId) ?: return false
        return turn.sessionId == session && turn.pauseRequestedAt == null &&
            state.canBecomeInterruptedOnProcessDeath() && state != TurnState.CANCELLING &&
            parent.name == call.toolName && (
                parent.state == ToolCallState.RUNNING.name ||
                    (continuing && parent.state == ToolCallState.COMPLETED.name)
            )
    }
}
