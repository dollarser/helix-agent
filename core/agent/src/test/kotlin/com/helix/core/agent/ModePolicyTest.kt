package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModePolicyTest {
    private fun profile(
        operationClass: ToolOperationClass,
        dynamicRisk: RiskLevel,
    ): ToolModeProfile = ToolModeProfile(operationClass, dynamicRisk)

    private fun deniedCode(decision: ModeDecision): ModeDenialCode {
        val denied = decision as? ModeDecision.Denied ?: error("expected denied, got $decision")
        return denied.code
    }

    @Test
    fun chatDefaultHasNoTools() {
        // Even a perfect READ_ONLY/L0 tool is denied before explicit enablement.
        val decision = ModePolicy.evaluate(AgentMode.CHAT, profile(ToolOperationClass.READ_ONLY, RiskLevel.L0))
        assertEquals(ModeDenialCode.TOOLS_DISABLED, deniedCode(decision))
        // The default Chat tool table is empty.
        val table =
            ModePolicy.filterTools(AgentMode.CHAT, listOf("read", "write", "bash"), chatToolsEnabled = false) { name ->
                when (name) {
                    "read" -> profile(ToolOperationClass.READ_ONLY, RiskLevel.L0)
                    "write" -> profile(ToolOperationClass.LOCAL_MUTATION, RiskLevel.L1)
                    else -> profile(ToolOperationClass.CODE_EXECUTION, RiskLevel.L1)
                }
            }
        assertTrue(table.isEmpty())
    }

    @Test
    fun chatEnabledAllowsReadOnlyL0() {
        val p = profile(ToolOperationClass.READ_ONLY, RiskLevel.L0)
        val decision = ModePolicy.evaluate(AgentMode.CHAT, p, chatToolsEnabled = true)
        assertTrue(decision is ModeDecision.Allowed)
    }

    @Test
    fun chatEnabledRejectsWriteEvenAtL0() {
        val p = profile(ToolOperationClass.LOCAL_MUTATION, RiskLevel.L0)
        val decision = ModePolicy.evaluate(AgentMode.CHAT, p, chatToolsEnabled = true)
        assertEquals(ModeDenialCode.OPERATION_CLASS_NOT_READ_ONLY, deniedCode(decision))
    }

    @Test
    fun chatEnabledRejectsReadOnlyAboveL0() {
        val p = profile(ToolOperationClass.READ_ONLY, RiskLevel.L1)
        val decision = ModePolicy.evaluate(AgentMode.CHAT, p, chatToolsEnabled = true)
        assertEquals(ModeDenialCode.RISK_LEVEL_TOO_HIGH, deniedCode(decision))
    }

    @Test
    fun planRejectsWriteHttpFetchBashBrowserClickUiClick() {
        // All at risk L1: the most permissive risk Plan would otherwise admit, proving the
        // operation-class check is primary and risk cannot substitute for it.
        val denials =
            listOf(
                "write" to ToolOperationClass.LOCAL_MUTATION,
                "http.fetch" to ToolOperationClass.NETWORK,
                "bash" to ToolOperationClass.CODE_EXECUTION,
                "browser.click" to ToolOperationClass.EXTERNAL_ACTION,
                "ui.click" to ToolOperationClass.EXTERNAL_ACTION,
            )
        for ((toolName, operationClass) in denials) {
            val decision = ModePolicy.evaluate(AgentMode.PLAN, profile(operationClass, RiskLevel.L1))
            assertEquals(
                "$toolName must be denied in Plan",
                ModeDenialCode.OPERATION_CLASS_NOT_READ_ONLY,
                deniedCode(decision),
            )
        }
    }

    @Test
    fun planAllowsReadOnlyAtL0AndL1() {
        for (risk in listOf(RiskLevel.L0, RiskLevel.L1)) {
            val decision = ModePolicy.evaluate(AgentMode.PLAN, profile(ToolOperationClass.READ_ONLY, risk))
            assertTrue("READ_ONLY at $risk must be allowed in Plan", decision is ModeDecision.Allowed)
        }
    }

    @Test
    fun planRejectsReadOnlyAboveL1() {
        val decision = ModePolicy.evaluate(AgentMode.PLAN, profile(ToolOperationClass.READ_ONLY, RiskLevel.L2))
        assertEquals(ModeDenialCode.RISK_LEVEL_TOO_HIGH, deniedCode(decision))
    }

    @Test
    fun actAndGoalDoNotRestrictAtModeLevel() {
        for (operationClass in ToolOperationClass.entries) {
            for (risk in RiskLevel.entries) {
                for (mode in listOf(AgentMode.ACT, AgentMode.GOAL)) {
                    val decision = ModePolicy.evaluate(mode, profile(operationClass, risk))
                    val message = "$mode allows $operationClass/$risk at mode level (Policy still decides)"
                    assertTrue(message, decision is ModeDecision.Allowed)
                }
            }
        }
    }

    @Test
    fun filterToolsKeepsOnlyAdmittedTools() {
        val tools =
            listOf(
                "read" to profile(ToolOperationClass.READ_ONLY, RiskLevel.L0),
                "write" to profile(ToolOperationClass.LOCAL_MUTATION, RiskLevel.L1),
                "http.fetch" to profile(ToolOperationClass.NETWORK, RiskLevel.L1),
            )

        fun profileOf(name: String) = tools.first { it.first == name }.second
        val planTable = ModePolicy.filterTools(AgentMode.PLAN, tools.map { it.first }, profileOf = ::profileOf)
        assertEquals(listOf("read"), planTable)

        val chatNames = tools.map { it.first }
        val chatTable =
            ModePolicy.filterTools(AgentMode.CHAT, chatNames, chatToolsEnabled = true, profileOf = ::profileOf)
        assertEquals(listOf("read"), chatTable)

        val actTable = ModePolicy.filterTools(AgentMode.ACT, tools.map { it.first }, profileOf = ::profileOf)
        assertEquals(listOf("read", "write", "http.fetch"), actTable)
    }
}
