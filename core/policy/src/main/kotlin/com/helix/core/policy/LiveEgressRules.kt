package com.helix.core.policy

import com.helix.core.model.SafetyProfile

/**
 * The high-sensitivity egress rules that are LIVE for Policy Engine evaluation right now
 * (HXA-068, ADR-0012). This is the single fail-closed gate between the persisted rule store and
 * the engine:
 *
 * - **Profile switch** (ADR-0012: 规则在 Profile 切换时 fail closed): rules auto-approve ONLY
 *   while the current [SafetyProfile] is ADVANCED. A STANDARD profile — or a consumer build,
 *   which can never be ADVANCED — yields NO rules, so every sensitive egress re-gates to
 *   per-call confirmation.
 * - **Storage** (ADR-0012: 存储损坏 fail closed): a [load]er that throws (unreadable store, a
 *   corrupt/undecodable row) yields NO rules rather than auto-approving a possibly-wrong set.
 *
 * The validity window and clock-rollback check are NOT here: [current] returns the user's full
 * bound set and the Policy Engine's `isLiveFor` discards expired / rolled-back rules per
 * evaluation (ADR-0005). Keeping the window check in the engine (not the loader) means a
 * clock-rewound device never matches a rule whose `createdAt` is in the future.
 */
object LiveEgressRules {
    fun current(
        profile: SafetyProfile,
        load: () -> List<HighSensitivityRule>,
    ): Set<HighSensitivityRule> {
        if (profile != SafetyProfile.ADVANCED) return emptySet()
        return runCatching { load() }.getOrDefault(emptyList()).toSet()
    }
}
