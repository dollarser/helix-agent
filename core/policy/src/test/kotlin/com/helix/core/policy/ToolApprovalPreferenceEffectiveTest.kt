package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HXA-200: [ToolApprovalResolver.effectivePreference] — the collapse of the stored per-scope
 * records (GLOBAL/WORKSPACE/SESSION) into the single effective preference the runtime applies.
 * Pins the ADR-0052 scope rules: DENY at any applicable scope is authoritative (a narrower ALLOW
 * cannot override it), otherwise ASK wins over ALLOW, and — the 2026-09-14
 * clarification — a stored ALLOW that no longer matches the current contract hash becomes an
 * effective ASK tagged [ToolApprovalReason.ALLOW_INVALIDATED] (distinct from [EffectiveToolPreference.Unset],
 * which is produced only when nothing was ever stored).
 */
class ToolApprovalPreferenceEffectiveTest {
    private fun record(
        preference: ToolApprovalPreference,
        scope: ToolApprovalPreferenceScope,
        contractHash: String? = null,
    ): ToolApprovalPreferenceRecord = ToolApprovalPreferenceRecord(preference, scope, contractHash)

    @Test
    fun `no stored records resolve to unset`() {
        // Nothing stored: Unset (the call keeps its original policy handling), NOT an ASK and NOT
        // the invalidated-ALLOW fallback.
        assertEquals(
            EffectiveToolPreference.Unset,
            ToolApprovalResolver.effectivePreference(emptyList(), "c1"),
        )
    }

    @Test
    fun `a live global allow resolves to allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1")),
                "c1",
            )
        assertEquals(EffectiveToolPreference.Allow, effective)
    }

    @Test
    fun `a contract change invalidates a stored allow to an invalidated ask`() {
        // The clarified point 6: a stale ALLOW is not "unset" — it is an ASK that carries the
        // invalidation reason, so the runtime can say "your ALLOW no longer applies".
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1")),
                "c2",
            )
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED), effective)
    }

    @Test
    fun `a stored deny resolves to deny regardless of the contract`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(record(ToolApprovalPreference.DENY, ToolApprovalPreferenceScope.GLOBAL)),
                "anything",
            )
        assertEquals(EffectiveToolPreference.Deny, effective)
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
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
    }

    @Test
    fun `a workspace ask restricts a session allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.GLOBAL, "c1"),
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.WORKSPACE),
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.SESSION, "c1"),
                ),
                "c1",
            )
        // Applicable ASK remains a restriction even in a broader scope.
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
    }

    @Test
    fun `a global ask restricts a workspace allow`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.GLOBAL),
                    record(ToolApprovalPreference.ALLOW, ToolApprovalPreferenceScope.WORKSPACE, "c1"),
                ),
                "c1",
            )
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
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
        assertEquals(EffectiveToolPreference.Deny, effective)
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
        // DENY > ALLOW: an outer DENY is authoritative even against a narrower session ALLOW.
        assertEquals(EffectiveToolPreference.Deny, effective)
    }

    @Test
    fun `an outer deny cannot be overridden by a narrower ask`() {
        val effective =
            ToolApprovalResolver.effectivePreference(
                listOf(
                    record(ToolApprovalPreference.DENY, ToolApprovalPreferenceScope.GLOBAL),
                    record(ToolApprovalPreference.ASK, ToolApprovalPreferenceScope.SESSION),
                ),
                "c1",
            )
        assertEquals(EffectiveToolPreference.Deny, effective)
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
        // The GLOBAL ALLOW is dropped (contract "old" != "new"); the live SESSION ASK wins, so the
        // provenance is an EXPLICIT ask, not the invalidated-ALLOW fallback.
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
    }
}
