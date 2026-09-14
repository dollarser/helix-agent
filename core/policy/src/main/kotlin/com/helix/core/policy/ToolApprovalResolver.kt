package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope

/**
 * One effective outcome of applying a user tool preference to a policy decision (ADR-0052).
 *
 * This is the single contract both the tool Registry (model exposure) and the ToolDispatcher
 * (pre-start re-resolution) must parse, so a user setting changed while a call is queued or
 * waiting cannot be resolved against a stale value (ADR-0052 point 7).
 */
sealed interface ToolApprovalResolution {
    /**
     * Proceed without presenting a per-call confirmation card. Only reachable for a policy
     * [PolicyDecision.Allow] (an L0/L1 call inside the authorized scope) combined with an
     * explicit [ToolApprovalPreference.ALLOW].
     */
    data object AutoProceed : ToolApprovalResolution

    /** Present the per-call confirmation card and wait for the user's one-shot decision. */
    data class RequiresCard(
        val detail: String,
    ) : ToolApprovalResolution

    /**
     * Block the call and persist the settled denial. [code] is stable for audit and UI. A
     * preference never makes an unavailable tool usable: a policy denial always wins.
     */
    data class Blocked(
        val code: ToolApprovalBlockCode,
        val detail: String,
    ) : ToolApprovalResolution
}

/** Stable reason a preference-resolved call is blocked, kept separate from policy denial codes. */
enum class ToolApprovalBlockCode {
    /** The policy engine denied the call (capability/mode/L3/egress); no preference overrides it. */
    POLICY_DENIED,

    /** The user's DENY preference blocks the tool. */
    PREFERENCE_DENIED,
}

/**
 * Whether a tool is exposed to the model at all, given the user's stored preference
 * (ADR-0052 point 4: DENY is effective at model exposure).
 */
enum class ToolApprovalExposure {
    /** The tool is visible to the model; runtime still resolves the preference per call. */
    EXPOSE,

    /** The user has DENYed the tool; it must not be offered to the model. */
    HIDDEN_BY_DENY,
}

/**
 * One stored tool-approval preference as the resolver consumes it: the three-state value, its
 * scope, and the descriptor `contractHash` an ALLOW was bound to (null for ASK/DENY). The storage
 * layer rehydrates a row into this; the caller feeds the applicable set to
 * [ToolApprovalResolver.effectivePreference].
 */
data class ToolApprovalPreferenceRecord(
    val preference: ToolApprovalPreference,
    val scope: ToolApprovalPreferenceScope,
    val contractHash: String?,
)

/**
 * Resolves a user [ToolApprovalPreference] against a [PolicyDecision] into the effective
 * handling for one tool call (ADR-0052). Pure and stateless: it takes the stored preference
 * (or null when the user never set one) and the policy engine's decision, and returns a
 * [ToolApprovalResolution] the caller (Registry or Dispatcher) acts on.
 *
 * Priority (ADR-0052 points 2-5):
 * 1. A policy [PolicyDecision.Deny] always wins — a preference can never make an unavailable
 *    tool usable (point 4) and can never expand capability or scope (point 2).
 * 2. A stored [ToolApprovalPreference.DENY] blocks the call.
 * 3. A policy [PolicyDecision.RequiresApproval] (L2/L3 or high-sensitivity egress) always
 *    presents a card, regardless of [ToolApprovalPreference.ALLOW] — ALLOW does not mint a
 *    wildcard high-risk proof (point 2).
 * 4. A policy [PolicyDecision.Allow] (an in-scope L0/L1 call) proceeds card-free only under an
 *    explicit [ToolApprovalPreference.ALLOW]; an ASK — or the unset default — forces a card.
 */
object ToolApprovalResolver {
    /**
     * The effective preference for a tool the user never configured.
     *
     * ADR-0052 fixes the unset default to [ToolApprovalPreference.ASK] ("new tools default to
     * ASK"), which also keeps ALLOW meaningful: with an ASK default, an explicit ALLOW is what
     * turns an in-scope low-risk call card-free. "Preserving the original behavior of an
     * unconfigured user" is honored at the persistence/migration layer — no batch-ALLOW rows are
     * seeded and existing single-approval records and egress rules are left intact — not by
     * weakening this contract. If the owner later wants unset to mean "original policy behavior",
     * change this single constant; every other rule stays the same.
     */
    val DEFAULT_PREFERENCE: ToolApprovalPreference = ToolApprovalPreference.ASK

    /**
     * Resolves the effective handling for one tool call.
     *
     * @param storedPreference the user's stored preference for this tool, or null when unset
     *   (resolves to [DEFAULT_PREFERENCE]).
     * @param decision the policy engine's decision for the same call.
     */
    fun resolve(
        storedPreference: ToolApprovalPreference?,
        decision: PolicyDecision,
    ): ToolApprovalResolution =
        when {
            // Policy denial is the most authoritative block: a preference can never make an
            // unavailable tool usable or expand a scope (ADR-0052 points 2, 4). It wins even when
            // the user has also DENYed the tool, so the recorded reason is the policy's.
            decision is PolicyDecision.Deny -> {
                ToolApprovalResolution.Blocked(
                    code = ToolApprovalBlockCode.POLICY_DENIED,
                    detail = decision.detail,
                )
            }

            // A stored DENY blocks at the execution boundary (point 4), overriding even a
            // high-risk card: the user said this tool must not run, so we do not merely ask.
            storedPreference == ToolApprovalPreference.DENY -> {
                ToolApprovalResolution.Blocked(
                    code = ToolApprovalBlockCode.PREFERENCE_DENIED,
                    detail = "Tool blocked by the user's DENY preference.",
                )
            }

            // L2/L3 or high-sensitivity egress always presents a card. ALLOW does not grant a
            // wildcard high-risk proof (point 2), so this ignores the (non-DENY) preference.
            decision is PolicyDecision.RequiresApproval -> {
                ToolApprovalResolution.RequiresCard(detail = decision.detail)
            }

            // An in-scope L0/L1 call the policy would auto-resolve: an explicit ALLOW keeps it
            // card-free; ASK — or the unset default — forces a card (points 1, 3).
            else -> {
                val effective = storedPreference ?: DEFAULT_PREFERENCE
                if (effective == ToolApprovalPreference.ALLOW) {
                    ToolApprovalResolution.AutoProceed
                } else {
                    ToolApprovalResolution.RequiresCard(detail = "User preference asks before this tool runs.")
                }
            }
        }

    /**
     * Resolves model exposure for a tool (ADR-0052 point 4). Only a stored DENY hides the tool;
     * ASK and ALLOW leave it visible, because ASK is a runtime restriction, not a removal.
     */
    fun exposure(storedPreference: ToolApprovalPreference?): ToolApprovalExposure =
        when (storedPreference) {
            ToolApprovalPreference.DENY -> ToolApprovalExposure.HIDDEN_BY_DENY
            ToolApprovalPreference.ASK, ToolApprovalPreference.ALLOW, null -> ToolApprovalExposure.EXPOSE
        }

    /**
     * Collapses the stored records applicable to one tool call into the single effective preference
     * (ADR-0052 points 5, 6, 7). Returns null when nothing live remains, which the caller treats as
     * the unset default ([DEFAULT_PREFERENCE]).
     *
     * Rule order:
     * 1. A stored ALLOW that no longer matches the current descriptor [currentContractHash] is
     *    dropped first — a contract or source change falls an ALLOW back to ASK while a stored DENY
     *    or ASK is kept (point 6). ASK and DENY never carry a contract binding, so they always stay
     *    live.
     * 2. A live DENY in any applicable scope is authoritative: a narrower ALLOW (or ASK) cannot
     *    override an outer DENY (point 5).
     * 3. Otherwise the narrowest present scope wins: SESSION over WORKSPACE over GLOBAL.
     */
    fun effectivePreference(
        records: List<ToolApprovalPreferenceRecord>,
        currentContractHash: String?,
    ): ToolApprovalPreference? {
        val live =
            records.filter {
                it.preference != ToolApprovalPreference.ALLOW ||
                    (it.contractHash != null && it.contractHash == currentContractHash)
            }
        if (live.any { it.preference == ToolApprovalPreference.DENY }) {
            return ToolApprovalPreference.DENY
        }
        return preferenceInScope(live, ToolApprovalPreferenceScope.SESSION)
            ?: preferenceInScope(live, ToolApprovalPreferenceScope.WORKSPACE)
            ?: preferenceInScope(live, ToolApprovalPreferenceScope.GLOBAL)
    }

    private fun preferenceInScope(
        records: List<ToolApprovalPreferenceRecord>,
        scope: ToolApprovalPreferenceScope,
    ): ToolApprovalPreference? = records.firstOrNull { it.scope == scope }?.preference
}
