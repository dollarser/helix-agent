package com.helix.extensions.mobileuse

import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation
import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentationFactory
import com.helix.extensions.mobileuse.automation.AutomationServiceController
import com.helix.extensions.plugin.NativeResource
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult

/** Presentation follows authorized calls on every backend, never grants scope or owns execution facts. */
internal class MobileUsePresentedExecutor(
    private val delegate: ToolExecutor,
    private val borrowPresentation: () -> NativeResource.Lease<AutomationRuntimePresentation>?,
    private val admissionError: (ExecutableToolCall) -> String?,
) : ToolExecutor {
    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
        AutomationServiceController.withPhysicalOperation {
            admissionError(call)?.let { return@withPhysicalOperation refused(it) }
            val reference =
                borrowPresentation()
                    ?: return@withPhysicalOperation refused("MOBILE_USE_PRESENTATION_UNAVAILABLE")
            reference.use { executePresented(call, it) }
        }

    @Suppress("ReturnCount") // Binding refusal and missing suppression are distinct pre-execution failures.
    private fun executePresented(
        call: ExecutableToolCall,
        reference: NativeResource.Lease<AutomationRuntimePresentation>,
    ): ToolExecutorResult {
        val presentation = reference.value
        if (!presentation.bind(call)) {
            return refused("MOBILE_USE_TASK_UNAVAILABLE: Open the conversation to check whether the task stopped.")
        }
        val guarded =
            call.copy(
                cancel =
                    object : CancelSignal {
                        override fun isCancelled() =
                            call.cancel.isCancelled() || !reference.accepting || !presentation.executionAllowed()
                    },
            )
        val hidden = presentation.hideForOperation(guarded) ?: return refused("OVERLAY_HIDE_NOT_CONFIRMED")
        return hidden.use {
            if (guarded.cancel.isCancelled()) {
                refused(
                    "MOBILE_USE_TASK_STOPPED: The task was stopped or taken over; do not retry automatically.",
                )
            } else {
                admissionError(guarded)?.let(::refused)
                    ?: AutomationRuntimePresentationFactory.withUsage(reference) { delegate.execute(guarded) }
            }
        }
    }

    private fun refused(detail: String) = ToolExecutorResult.Failed(detail, sideEffectFree = true)
}
