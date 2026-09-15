package com.helix.core.policy

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolOperationClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant

/** Composes the real PolicyEngine with preferences, rather than supplying synthetic decisions. */
@RunWith(Parameterized::class)
class ToolPreferencePolicyMatrixTest(
    private val index: Int,
) {
    @Test
    fun preferencesNeverBypassModeRiskOrCapabilityPolicy() {
        val mode = AgentMode.entries[index % 4]
        val risk = RiskLevel.entries[(index / 4) % 4]
        val profile = SafetyProfile.entries[(index / 16) % 2]
        val operation = listOf(ToolOperationClass.READ_ONLY, ToolOperationClass.LOCAL_MUTATION)[(index / 32) % 2]
        val missing = (index / 64) % 2 == 1
        val chatEnabled = (index / 128) % 2 == 1
        val preference =
            listOf(
                EffectiveToolPreference.Unset,
                EffectiveToolPreference.Allow,
                EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT),
                EffectiveToolPreference.Deny,
            )[(index / 256) % 4]
        val engine =
            PolicyEngine(
                object : Clock {
                    override fun now(): Instant = Instant.parse("2026-09-15T00:00:00Z")
                },
            )
        val policy =
            engine
                .evaluate(
                    PolicyInput(
                        baseRisk = risk,
                        operationClass = operation,
                        mode = mode,
                        chatToolsEnabled = chatEnabled,
                        profile = profile,
                        source = ToolCallSource.BuiltIn,
                        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                        dataOrigin = DataOrigin.WORKSPACE,
                        scope = WorkspaceScope("matrix"),
                        missingCapabilities = if (missing) setOf(Capability.SAF_DOCUMENT_TREE) else emptySet(),
                    ),
                    emptySet(),
                ).decision
        val result = ToolApprovalResolver.resolve(preference, policy)
        val context = "$mode/$risk/$profile/$operation/missing=$missing/chat=$chatEnabled/$preference"
        if (missing) assertTrue(context, result is ToolApprovalResolution.Blocked)
        when {
            policy is PolicyDecision.Deny -> {
                assertTrue(context, result is ToolApprovalResolution.Blocked)
                assertEquals(ToolApprovalBlockCode.POLICY_DENIED, (result as ToolApprovalResolution.Blocked).code)
            }

            preference == EffectiveToolPreference.Deny -> {
                assertTrue(context, result is ToolApprovalResolution.Blocked)
            }

            policy is PolicyDecision.RequiresApproval || preference is EffectiveToolPreference.Ask -> {
                assertTrue(context, result is ToolApprovalResolution.RequiresCard)
            }

            else -> {
                assertTrue(context, result is ToolApprovalResolution.AutoProceed)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "case={0}")
        fun cases(): List<Array<Int>> = (0 until 1024).map { arrayOf(it) }
    }
}
