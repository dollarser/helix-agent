package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-200: the pure preference-resolution contract from ADR-0052. These tests pin the
 * three-state × policy-decision matrix, the DENY > ASK > ALLOW priority, the rule that a
 * policy denial always wins, and the invariant that ALLOW never mints a high-risk proof or
 * makes an unavailable tool usable. No Room/Dispatcher is involved here; the real execution
 * wiring is covered by the device test in HXA-200's P2/P3.
 */
class ToolApprovalResolverTest {
    private val allow = PolicyDecision.Allow
    private val deny = PolicyDecision.Deny(PolicyDenialCode.CAPABILITY_NOT_GRANTED, "capability not granted")
    private val highRisk = PolicyDecision.RequiresApproval("L2 call requires confirmation")

    @Test
    fun `allow keeps an in scope low risk call card free`() {
        assertSame(
            ToolApprovalResolution.AutoProceed,
            ToolApprovalResolver.resolve(ToolApprovalPreference.ALLOW, allow),
        )
    }

    @Test
    fun `ask forces a card even for an in scope low risk call`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.ASK, allow)
        assertTrue(resolution is ToolApprovalResolution.RequiresCard)
    }

    @Test
    fun `deny blocks an in scope low risk call with the preference code`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.DENY, allow)
        assertTrue(resolution is ToolApprovalResolution.Blocked)
        assertEquals(ToolApprovalBlockCode.PREFERENCE_DENIED, (resolution as ToolApprovalResolution.Blocked).code)
    }

    @Test
    fun `unset preference resolves to the ASK default`() {
        val resolution = ToolApprovalResolver.resolve(null, allow)
        assertSame(ToolApprovalPreference.ASK, ToolApprovalResolver.DEFAULT_PREFERENCE)
        assertTrue(resolution is ToolApprovalResolution.RequiresCard)
    }

    @Test
    fun `policy denial wins over an allow preference`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.ALLOW, deny)
        assertTrue(resolution is ToolApprovalResolution.Blocked)
        assertEquals(ToolApprovalBlockCode.POLICY_DENIED, (resolution as ToolApprovalResolution.Blocked).code)
    }

    @Test
    fun `policy denial wins over an unset preference`() {
        val resolution = ToolApprovalResolver.resolve(null, deny)
        assertTrue(resolution is ToolApprovalResolution.Blocked)
        assertEquals(ToolApprovalBlockCode.POLICY_DENIED, (resolution as ToolApprovalResolution.Blocked).code)
    }

    @Test
    fun `allow does not auto approve a high risk call`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.ALLOW, highRisk)
        assertTrue(resolution is ToolApprovalResolution.RequiresCard)
    }

    @Test
    fun `ask keeps a high risk call requiring a card`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.ASK, highRisk)
        assertTrue(resolution is ToolApprovalResolution.RequiresCard)
    }

    @Test
    fun `deny blocks a high risk call with the preference code`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.DENY, highRisk)
        assertTrue(resolution is ToolApprovalResolution.Blocked)
        assertEquals(ToolApprovalBlockCode.PREFERENCE_DENIED, (resolution as ToolApprovalResolution.Blocked).code)
    }

    @Test
    fun `policy denial wins over a deny preference`() {
        val resolution = ToolApprovalResolver.resolve(ToolApprovalPreference.DENY, deny)
        assertTrue(resolution is ToolApprovalResolution.Blocked)
        assertEquals(ToolApprovalBlockCode.POLICY_DENIED, (resolution as ToolApprovalResolution.Blocked).code)
    }

    // Model exposure: only DENY hides the tool; ASK and ALLOW (and unset) leave it visible.
    @Test
    fun `deny hides the tool from model exposure`() {
        assertEquals(ToolApprovalExposure.HIDDEN_BY_DENY, ToolApprovalResolver.exposure(ToolApprovalPreference.DENY))
    }

    @Test
    fun `ask and allow and unset keep the tool exposed`() {
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(ToolApprovalPreference.ASK))
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(ToolApprovalPreference.ALLOW))
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(null))
    }
}
