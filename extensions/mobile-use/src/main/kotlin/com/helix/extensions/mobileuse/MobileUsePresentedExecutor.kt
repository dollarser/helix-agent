package com.helix.extensions.mobileuse

import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation
import com.helix.extensions.mobileuse.automation.AutomationServiceController
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult

/** Presentation follows authorized calls on every backend, never grants scope or owns execution facts. */
internal class MobileUsePresentedExecutor(
    private val delegate: ToolExecutor,
    private val presentation: AutomationRuntimePresentation,
    private val authorized: (ExecutableToolCall) -> Boolean,
) : ToolExecutor {
    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
        AutomationServiceController.withPhysicalOperation {
            if (!authorized(call) || !presentation.bind(call)) {
                return@withPhysicalOperation refused("MOBILE_USE_TASK_NOT_ACTIVE")
            }
            val guarded =
                call.copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = call.cancel.isCancelled() || !presentation.executionAllowed()
                        },
                )
            val hidden =
                presentation.hideForOperation(guarded)
                    ?: return@withPhysicalOperation refused("OVERLAY_HIDE_NOT_CONFIRMED")
            hidden.use {
                if (guard(guarded)) delegate.execute(guarded) else refused("MOBILE_USE_TASK_NOT_ACTIVE")
            }
        }

    private fun guard(call: ExecutableToolCall) = !call.cancel.isCancelled() && authorized(call)

    private fun refused(detail: String) = ToolExecutorResult.Failed(detail, sideEffectFree = true)
}
