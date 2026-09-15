package com.helix.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-200: the pure preference-resolution contract from ADR-0052 (2026-09-14 clarification to
 * point 1). These tests pin the effective-preference × policy-decision matrix, the DENY > ASK >
 * ALLOW priority, the rule that a policy denial always wins, and the invariant that ALLOW never
 * mints a high-risk proof or makes an unavailable tool usable. The clarification is the load-bearing
 * case: an UNSET tool keeps its ORIGINAL policy behavior (card-free for an in-scope low-risk Allow)
 * instead of being forced to ASK, while an explicit ASK — or an ALLOW a contract change invalidated
 * — still forces a card, and the resolution carries WHICH of those it is. No Room/Dispatcher is
 * involved here; the real execution wiring is covered by the device test in HXA-200's P2/P3.
 */
class ToolApprovalResolverTest {
    private val allow = PolicyDecision.Allow
    private val deny = PolicyDecision.Deny(PolicyDenialCode.CAPABILITY_NOT_GRANTED, "capability not granted")
    private val highRisk = PolicyDecision.RequiresApproval("L2 call requires confirmation")

    @Test
    fun `an explicit allow keeps an in scope low risk call card free`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Allow, allow)
        val auto = resolution as ToolApprovalResolution.AutoProceed
        assertEquals(ToolApprovalReason.EXPLICIT, auto.reason)
    }

    @Test
    fun `an unset tool keeps the original card free behavior`() {
        // The clarified point 1: nothing stored is NOT an ASK. An in-scope low-risk Allow proceeds
        // exactly as the original (pre-feature) policy did.
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Unset, allow)
        val auto = resolution as ToolApprovalResolution.AutoProceed
        assertEquals(ToolApprovalReason.UNSET, auto.reason)
    }

    @Test
    fun `an explicit ask forces a card even for an in scope low risk call`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), allow)
        val card = resolution as ToolApprovalResolution.RequiresCard
        assertEquals(ToolApprovalReason.EXPLICIT, card.reason)
    }

    @Test
    fun `an invalidated allow falls back to a card with its own reason`() {
        val resolution =
            ToolApprovalResolver.resolve(
                EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED),
                allow,
            )
        val card = resolution as ToolApprovalResolution.RequiresCard
        assertEquals(ToolApprovalReason.ALLOW_INVALIDATED, card.reason)
    }

    @Test
    fun `a new tool default forces a card`() {
        // NEW_DEFAULT is only produced by a trusted registration/upgrade baseline; the resolver still
        // pins that such a tool cards rather than proceeding.
        val resolution =
            ToolApprovalResolver.resolve(
                EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT),
                allow,
            )
        val card = resolution as ToolApprovalResolution.RequiresCard
        assertEquals(ToolApprovalReason.NEW_DEFAULT, card.reason)
    }

    @Test
    fun `deny blocks an in scope low risk call with the preference code`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Deny, allow)
        val blocked = resolution as ToolApprovalResolution.Blocked
        assertEquals(ToolApprovalBlockCode.PREFERENCE_DENIED, blocked.code)
        assertEquals(ToolApprovalReason.EXPLICIT, blocked.reason)
    }

    @Test
    fun `policy denial wins over an allow preference`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Allow, deny)
        val blocked = resolution as ToolApprovalResolution.Blocked
        assertEquals(ToolApprovalBlockCode.POLICY_DENIED, blocked.code)
        assertEquals(ToolApprovalReason.POLICY, blocked.reason)
    }

    @Test
    fun `policy denial wins over an unset preference`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Unset, deny)
        val blocked = resolution as ToolApprovalResolution.Blocked
        assertEquals(ToolApprovalBlockCode.POLICY_DENIED, blocked.code)
    }

    @Test
    fun `allow does not auto approve a high risk call`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Allow, highRisk)
        val card = resolution as ToolApprovalResolution.RequiresCard
        assertEquals(ToolApprovalReason.POLICY, card.reason)
    }

    @Test
    fun `ask keeps a high risk call requiring a card`() {
        val resolution =
            ToolApprovalResolver.resolve(
                EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT),
                highRisk,
            )
        assertTrue(resolution is ToolApprovalResolution.RequiresCard)
    }

    @Test
    fun `deny blocks a high risk call with the preference code`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Deny, highRisk)
        val blocked = resolution as ToolApprovalResolution.Blocked
        assertEquals(ToolApprovalBlockCode.PREFERENCE_DENIED, blocked.code)
    }

    @Test
    fun `policy denial wins over a deny preference`() {
        val resolution = ToolApprovalResolver.resolve(EffectiveToolPreference.Deny, deny)
        val blocked = resolution as ToolApprovalResolution.Blocked
        assertEquals(ToolApprovalBlockCode.POLICY_DENIED, blocked.code)
        assertEquals(ToolApprovalReason.POLICY, blocked.reason)
    }

    // Model exposure: only a live DENY hides the tool; unset, allow and every ask variant stay visible.
    @Test
    fun `deny hides the tool from model exposure`() {
        assertEquals(ToolApprovalExposure.HIDDEN_BY_DENY, ToolApprovalResolver.exposure(EffectiveToolPreference.Deny))
    }

    @Test
    fun `ask and allow and unset keep the tool exposed`() {
        assertEquals(
            ToolApprovalExposure.EXPOSE,
            ToolApprovalResolver.exposure(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT)),
        )
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(EffectiveToolPreference.Allow))
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(EffectiveToolPreference.Unset))
    }
}
