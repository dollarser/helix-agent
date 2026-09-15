package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.PolicyDecision
import com.helix.core.policy.ToolApprovalPreferenceRecord
import com.helix.core.policy.ToolApprovalReason
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.policy.ToolPreferenceSnapshot
import com.helix.tools.framework.PreferenceDecisionAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceAuditPayloadTest {
    @Test fun snapshotRoundTripPreservesRevisionWithoutScopeOrPolicyProse() {
        val records =
            listOf(
                ToolApprovalPreferenceRecord(
                    ToolApprovalPreference.ALLOW,
                    ToolApprovalPreferenceScope.WORKSPACE,
                    "old-contract",
                    "rule-id",
                    7,
                    "/private/workspace",
                ),
            )
        val effective = ToolApprovalResolver.effectivePreference(records, "current")
        val policy = PolicyDecision.RequiresApproval("sensitive policy detail")
        val audit =
            PreferenceDecisionAudit(
                ToolPreferenceSnapshot(effective, records),
                "https://private-provider.example/token",
                "current",
                policy,
                ToolApprovalResolver.resolve(effective, policy),
            )
        val encoded = PreferenceAuditPayload.encode(audit).toString()
        assertFalse(encoded.contains("private"))
        assertFalse(encoded.contains("sensitive policy detail"))
        val decoded = requireNotNull(PreferenceAuditPayload.decode(encoded))
        assertEquals(1, decoded.version)
        assertEquals("ASK", decoded.effective)
        assertEquals(ToolApprovalReason.ALLOW_INVALIDATED, decoded.source)
        assertEquals(ToolApprovalReason.POLICY, decoded.reason)
        assertEquals("rule-id", decoded.rules.single().id)
        assertEquals(7L, decoded.rules.single().revision)
        assertFalse(decoded.rules.single().contractValid)
        assertEquals(
            64,
            decoded.rules
                .single()
                .scopeHash!!
                .length,
        )
        assertNull(PreferenceAuditPayload.decode(encoded.replace("\"version\":1", "\"version\":2")))
        assertNull(PreferenceAuditPayload.decode("null"))
    }

    @Test fun unsetAndNewDefaultRemainDistinctWithoutFabricatedRules() {
        listOf(EffectiveToolPreference.Unset, EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT)).forEach {
            val audit =
                PreferenceDecisionAudit(
                    ToolPreferenceSnapshot(it),
                    "builtin",
                    "contract",
                    PolicyDecision.Allow,
                    ToolApprovalResolver.resolve(it, PolicyDecision.Allow),
                )
            val decoded = requireNotNull(PreferenceAuditPayload.decode(PreferenceAuditPayload.encode(audit).toString()))
            assertTrue(decoded.rules.isEmpty())
            assertEquals(
                if (it ==
                    EffectiveToolPreference.Unset
                ) {
                    ToolApprovalReason.UNSET
                } else {
                    ToolApprovalReason.NEW_DEFAULT
                },
                decoded.source,
            )
        }
    }
}
