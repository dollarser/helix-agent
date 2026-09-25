package com.helix.app.agent

import com.helix.core.model.ToolOperationClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnresolvedEffectPolicyTest {
    @Test
    fun unresolvedEffectsAllowOnlyReadOnlyTools() {
        assertTrue(UnresolvedEffectPolicy.permits(true, ToolOperationClass.READ_ONLY))
        for (operationClass in ToolOperationClass.entries.filterNot { it == ToolOperationClass.READ_ONLY }) {
            assertFalse(operationClass.name, UnresolvedEffectPolicy.permits(true, operationClass))
        }
    }

    @Test
    fun noUnresolvedEffectLeavesNormalPolicyInCharge() {
        for (operationClass in ToolOperationClass.entries) {
            assertTrue(operationClass.name, UnresolvedEffectPolicy.permits(false, operationClass))
        }
    }
}
