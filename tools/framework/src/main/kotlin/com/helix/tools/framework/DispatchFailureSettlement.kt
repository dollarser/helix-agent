package com.helix.tools.framework

/** Classifies only trusted framework/broker state; a generic dependency error remains a failure. */
internal fun thrownDispatchOutcome(
    cancel: CancelSignal,
    executionStartedAt: Long?,
    failure: Throwable,
): ToolDispatchOutcome =
    if ((cancel.isCancelled() || failure is ApprovalWaitCancelledException) && executionStartedAt == null) {
        ToolDispatchOutcome.Cancelled
    } else {
        ToolDispatchOutcome.ExecutionFailed(
            DispatchOutcomeCode.TOOL_FAILED,
            "unexpected dispatch failure: ${failure::class.simpleName}",
        )
    }

/** Audit failure must propagate too, retaining the original failure as evidence. */
@Suppress("TooGenericExceptionCaught")
internal fun auditFailurePreservingCause(
    failure: Throwable,
    record: () -> Unit,
) {
    try {
        record()
    } catch (auditFailure: Throwable) {
        auditFailure.addSuppressed(failure)
        throw auditFailure
    }
}
