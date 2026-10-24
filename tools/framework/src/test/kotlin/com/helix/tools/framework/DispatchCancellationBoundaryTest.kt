package com.helix.tools.framework

import com.helix.core.model.ApprovalWaitCancelledException
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolDispatchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchCancellationBoundaryTest {
    @Test fun commonApprovalWaitCancellationOnlyProvesNoExecutionBeforeStart() {
        val cancellation = ApprovalWaitCancelledException("synthetic pending request cancelled")
        assertEquals(ToolDispatchOutcome.Cancelled, thrownDispatchOutcome(NoCancellation, null, cancellation))
        assertTrue(thrownDispatchOutcome(NoCancellation, 1000L, cancellation) is ToolDispatchOutcome.ExecutionFailed)
    }

    @Test fun arbitraryFailureDoesNotClaimCancellationBeforeExecution() {
        assertTrue(
            thrownDispatchOutcome(NoCancellation, null, IllegalStateException("synthetic failure"))
                is ToolDispatchOutcome.ExecutionFailed,
        )
    }
}
