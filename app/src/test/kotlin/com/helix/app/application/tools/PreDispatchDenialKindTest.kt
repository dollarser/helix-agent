package com.helix.app.application.tools

import com.helix.app.application.tools.PreDispatchDenialKind
import com.helix.core.model.ToolCallState
import com.helix.tools.framework.DecisionSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Persisted state and decision source retain framework failure versus policy denial. */
class PreDispatchDenialKindTest {
    @Test
    fun frameworkRejectionIsFailedWithFrameworkDecisionSource() {
        val kind = PreDispatchDenialKind.FRAMEWORK_REJECTED
        assertEquals(ToolCallState.FAILED, kind.state)
        assertEquals(DecisionSource.FRAMEWORK, kind.decisionSource)
    }

    @Test
    fun recoveryReviewRemainsDeniedByPolicy() {
        val kind = PreDispatchDenialKind.RECOVERY_REVIEW_REQUIRED
        assertEquals(ToolCallState.DENIED, kind.state)
        assertEquals(DecisionSource.POLICY, kind.decisionSource)
    }
}
