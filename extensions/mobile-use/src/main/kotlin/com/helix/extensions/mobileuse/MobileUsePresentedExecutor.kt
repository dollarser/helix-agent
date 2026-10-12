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
    private val retainOverlay: Boolean = false,
    private val admissionError: (ExecutableToolCall) -> String?,
) : ToolExecutor {
    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
        AutomationServiceController.withPhysicalOperation {
            preExecutionOutcome(call)?.let { return@withPhysicalOperation it }
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
        if (retainOverlay) return executeBound(guarded, reference)
        val hidden =
            presentation.hideForOperation(guarded)
                ?: return preExecutionOutcome(guarded) ?: refused(
                    "OVERLAY_HIDE_NOT_CONFIRMED: Helix could not confirm overlay removal. " +
                        "No action was dispatched. Retry only after observable recovery; " +
                        "do not repeat unchanged requests or bypass overlay hiding.",
                )
        return hidden.use { executeBound(guarded, reference) }
    }

    private fun executeBound(
        call: ExecutableToolCall,
        reference: NativeResource.Lease<AutomationRuntimePresentation>,
    ): ToolExecutorResult =
        preExecutionOutcome(call)
            ?: AutomationRuntimePresentationFactory.withUsage(reference) { delegate.execute(call) }

    private fun preExecutionOutcome(call: ExecutableToolCall): ToolExecutorResult? =
        when {
            call.cancel.isCancelled() -> ToolExecutorResult.Cancelled

            !java.time.Instant
                .now()
                .isBefore(call.deadline) -> ToolExecutorResult.TimedOut

            else -> admissionError(call)?.let(::refused)
        }

    private fun refused(detail: String) = ToolExecutorResult.Failed(detail, sideEffectFree = true)
}

/** Classification comes from the trusted registered descriptor, never tool arguments or model metadata. */
internal fun retainsMobileUseOverlay(toolName: String): Boolean =
    toolName in setOf("ui.snapshot", "ui.find", "ui.wait", "ui.device", "ui.apps")
