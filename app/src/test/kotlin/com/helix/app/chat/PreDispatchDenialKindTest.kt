package com.helix.app.chat

import com.helix.app.R
import com.helix.core.model.ToolCallState
import com.helix.tools.framework.DecisionSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Persisted state and timeline label must agree without recasting a framework error as user denial. */
class PreDispatchDenialKindTest {
    @Test
    fun frameworkRejectionIsFailedInStorageAndPresentation() {
        val kind = PreDispatchDenialKind.FRAMEWORK_REJECTED
        assertEquals(ToolCallState.FAILED, kind.state)
        assertEquals(DecisionSource.FRAMEWORK, kind.decisionSource)
        assertEquals(R.string.tool_state_failed, kind.stateLabel)
    }

    @Test
    fun recoveryReviewRemainsDeniedByPolicy() {
        val kind = PreDispatchDenialKind.RECOVERY_REVIEW_REQUIRED
        assertEquals(ToolCallState.DENIED, kind.state)
        assertEquals(DecisionSource.POLICY, kind.decisionSource)
        assertEquals(R.string.tool_state_denied, kind.stateLabel)
    }
}
