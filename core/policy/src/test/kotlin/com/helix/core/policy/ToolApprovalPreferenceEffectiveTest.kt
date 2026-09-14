package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HXA-200: [ToolApprovalResolver.effectivePreference] — the collapse of the stored per-scope
 * records (GLOBAL/WORKSPACE/SESSION) into the single preference the runtime applies. Pins the
 * ADR-0052 scope rules: DENY at any applicable scope is authoritative (a narrower ALLOW cannot
 * override it), otherwise the narrowest present scope wins, and a stored ALLOW that no longer
 * matches the current contract hash is dropped back to the unset default while DENY/ASK survive.
 */
class ToolApprovalPreferenceEffectiveTest {
    private fun record(
        preference: ToolApprovalPreference,
        scope: ToolApprovalPreferenceScope,
        contractHash: String? = null,
    ): ToolApprovalPreferenceRecord = ToolApprovalPreferenceRecord(preference, scope, contractHash)

    @Test
    fun `no stored records resolve to unset`() {
        assertNull(ToolApprovalResolver.effectivePreference(emptyList(), "c1"))
    }

    @Test
    fun `a live global allow resolves to allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1")),
                "c1",
            )
        assertEquals(ToolApprovalPreference.ALLOW, effective)
    }

    @Test
    fun `a contract change invalidates a stored allow back to unset`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1")),
                "c2",
            )
        assertNull(effective)
    }

    @Test
    fun `a stored deny resolves to deny regardless of the contract`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(record(ToolApprovalPreference.DENY, ToolApprovalPreferenceScope.GLOBAL)),
                "anything",
            )
        assertEquals(ToolApprovalPreference.DENY, effective)
    }

    @Test
    fun `a narrower session ask wins over a global allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1"),
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.SESSION),
                ),
                "c1",
            )
        assertEquals(ToolApprovalPreference.ASK, effective)
    }

    @Test
    fun `the most specific scope present wins`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1"),
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.WORKSPACE),
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.SESSION, "c1"),
                ),
                "c1",
            )
        // SESSION is present and narrowest, so it beats the WORKSPACE ASK and the GLOBAL ALLOW.
        assertEquals(ToolApprovalPreference.ALLOW, effective)
    }

    @Test
    fun `a workspace allow wins over a global ask but a session ask still wins over it`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.GLOBAL),
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.WORKSPACE, "c1"),
                ),
                "c1",
            )
        assertEquals(ToolApprovalPreference.ALLOW, effective)
    }

    @Test
    fun `a narrower deny overrides an outer allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1"),
                    record(ToolApprovalPreference.DENY, ToolApprovalPreferenceScope.SESSION),
                ),
                "c1",
            )
        assertEquals(ToolApprovalPreference.DENY, effective)
    }

    @Test
    fun `an outer deny cannot be overridden by a narrower allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.DENY, ToolApprovalPreferenceScope.GLOBAL),
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.SESSION, "c1"),
                ),
                "c1",
            )
        assertEquals(ToolApprovalPreference.DENY, effective)
    }

    @Test
    fun `an invalidated allow leaves a live session ask in force`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "old"),
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.SESSION),
                ),
                "new",
            )
        // The GLOBAL ALLOW is dropped (contract "old" != "new"), the SESSION ASK stays.
        assertEquals(ToolApprovalPreference.ASK, effective)
    }
}
