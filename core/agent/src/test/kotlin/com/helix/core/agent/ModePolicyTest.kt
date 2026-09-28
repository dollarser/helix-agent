package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.isReviewModeAdmitted
import org.junit.Assert.assertEquals
import org.junit.Test

class ModePolicyTest {
    @Suppress("NestedBlockDepth") // exhaustive mode / operation / opt-in matrix
    @Test
    fun everyModeAndOperationUsesTheExplicitContract() {
        for (mode in AgentMode.entries) {
            for (operation in ToolOperationClass.entries) {
                for (enabled in listOf(false, true)) {
                    val actual = ModePolicy.evaluate(mode, ToolModeProfile(operation), enabled)
                    val admitted =
                        when (mode) {
                            AgentMode.CHAT -> enabled && operation.isReviewModeAdmitted
                            AgentMode.PLAN -> operation.isReviewModeAdmitted
                            AgentMode.ACT, AgentMode.GOAL -> true
                        }
                    assertEquals("$mode $operation $enabled", admitted, actual is ModeDecision.Allowed)
                }
            }
        }
    }

    @Test fun filteringKeepsOnlyTheAdmittedOperations() {
        assertEquals(
            listOf(ToolOperationClass.READ_ONLY, ToolOperationClass.METADATA),
            ModePolicy.filterTools(AgentMode.PLAN, ToolOperationClass.entries) { ToolModeProfile(it) },
        )
        assertEquals(
            emptyList<ToolOperationClass>(),
            ModePolicy.filterTools(AgentMode.CHAT, ToolOperationClass.entries) { ToolModeProfile(it) },
        )
    }
}
