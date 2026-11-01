package com.helix.app.runtimebridge

import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.HostToolBridgeProvider
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.jsonPrimitive

/** Open only after the original call passed Dispatcher; arguments never contain transport credentials. */
internal class ScriptToolBridgeExecutor(
    private val delegate: ToolExecutor,
    private val provider: HostToolBridgeProvider,
) : ToolExecutor {
    @Suppress("ReturnCount") // Distinct ordinary execution and pre-runtime refusal.
    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
        if (call.args["toolBridge"]?.jsonPrimitive?.content != "true") return delegate.execute(call)
        val lease =
            try {
                provider.open(call)
            } catch (_: IllegalStateException) {
                return ToolExecutorResult.Failed(
                    "HOST_TOOL_BRIDGE_UNAVAILABLE: Enable script tool delegation in Runtime settings; " +
                        "the original active task and registered tool permissions are still required.",
                    sideEffectFree = true,
                )
            }
        return try {
            val guarded =
                call.copy(
                    hostToolBridge = lease,
                    cancel =
                        object : com.helix.core.model.CancelSignal {
                            override fun isCancelled() = call.cancel.isCancelled() || lease.reviewRequired
                        },
                )
            reviewUnresolvedScript(delegate.execute(guarded), lease.unresolvedCallIds)
        } finally {
            lease.close()
        }
    }
}
