package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
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

    // Scope-merge (point 5) for the ASK/ALLOW axis: effectivePreference collapses the stored
    // records for one tool into the single effective preference. DENY is the only
    // cross-scope-authoritative state; for ASK vs ALLOW the narrowest present scope wins. HXA-200
    // Gap 1's load-bearing case is a GLOBAL ASK with a NARROWER session ALLOW: the session ALLOW
    // preempts the global ASK (card-free), the opposite of the DENY case above where the outer
    // DENY is authoritative.

    @Test
    fun aNarrowerSessionAllowOverridesAGlobalAsk() {
        // A standing GLOBAL ASK plus a NARROWER, still-live session ALLOW (matching contract): the
        // narrowest scope wins, so the effective preference is Allow (not the global ASK). This is
        // exactly "全局 ASK + 窄 scope ALLOW" — it resolves card-free, not to a card.
        val records =
            listOf(
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.GLOBAL, null),
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.SESSION, "c1"),
            )
        assertEquals(EffectiveToolPreference.Allow, ToolApprovalResolver.effectivePreference(records, "c1"))
    }

    @Test
    fun aNarrowerSessionAskOverridesAnOuterAllow() {
        // The mirror image: a GLOBAL ALLOW (live) plus a NARROWER session ASK. The narrower ASK
        // wins — a session-level restriction tightens a broader standing ALLOW.
        val records =
            listOf(
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1"),
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.SESSION, null),
            )
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT),
            ToolApprovalResolver.effectivePreference(records, "c1"),
        )
    }

    @Test
    fun aWorkspaceAskSitsBetweenAGlobalAllowAndASessionDenyFreeCase() {
        // Global ALLOW + workspace ASK, no session record: the narrowest present scope is the
        // WORKSPACE ASK (over the GLOBAL ALLOW), so the effective preference is an explicit Ask.
        val records =
            listOf(
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1"),
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.WORKSPACE, null),
            )
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT),
            ToolApprovalResolver.effectivePreference(records, "c1"),
        )
    }

    @Test
    fun anOuterDenyBeatsEveryNarrowerAskAndAllow() {
        // The DENY asymmetry (point 5): an outer GLOBAL DENY is authoritative even over a live,
        // contract-matching session ALLOW and a workspace ASK. This is the one case where a
        // narrower record does NOT win.
        val records =
            listOf(
                ToolApprovalPreferenceRecord(ToolApprovalPreference.DENY, ToolApprovalPreferenceScope.GLOBAL, null),
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.WORKSPACE, null),
                ToolApprovalPreferenceRecord(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.SESSION, "c1"),
            )
        assertEquals(EffectiveToolPreference.Deny, ToolApprovalResolver.effectivePreference(records, "c1"))
    }

    @Test
    fun aStaleAllowWithNoOtherLiveRecordIsAnInvalidatedAsk() {
        // The only record is an ALLOW whose contract no longer matches: it is dropped by the
        // liveness filter, but the outcome is an Ask tagged ALLOW_INVALIDATED — NOT a fresh Unset,
        // so "your ALLOW no longer applies" is distinct from "you never set anything" (points 6, 8).
        val records =
            listOf(
                ToolApprovalPreferenceRecord(
                    ToolApprovalPreference.ALLOW,
                    ToolApprovalPreferenceScope.GLOBAL,
                    "stale",
                ),
            )
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED),
            ToolApprovalResolver.effectivePreference(records, "c1"),
        )
    }

    @Test
    fun noStoredRecordAtAllIsUnset() {
        // Nothing stored: the effective preference is Unset and the call keeps its original policy
        // handling (the clarified point 1) — never a fabricated ASK.
        assertEquals(EffectiveToolPreference.Unset, ToolApprovalResolver.effectivePreference(emptyList(), "c1"))
    }
}
