package com.helix.app.agent

import com.helix.core.model.ToolOperationClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnresolvedEffectPolicyTest {
    @Test
    fun automaticInspectionRemainsWithinItsReadOnlyAuthorization() {
        assertTrue(UnresolvedEffectPolicy.permitsInspection(true, ToolOperationClass.READ_ONLY))
        for (operationClass in ToolOperationClass.entries.filterNot { it == ToolOperationClass.READ_ONLY }) {
            assertFalse(operationClass.name, UnresolvedEffectPolicy.permitsInspection(true, operationClass))
        }
    }

    @Test
    fun ordinaryUserTasksLeaveNormalPolicyInChargeRegardlessOfHistoricalResults() {
        for (operationClass in ToolOperationClass.entries) {
            assertTrue(operationClass.name, UnresolvedEffectPolicy.permitsInspection(false, operationClass))
        }
    }
}
