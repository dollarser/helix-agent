package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope

/**
 * One effective outcome of applying a user tool preference to a policy decision (ADR-0052).
 *
 * This is the single contract both the tool Registry (model exposure) and the ToolDispatcher
 * (pre-start re-resolution) must parse, so a user setting changed while a call is queued or
 * waiting cannot be resolved against a stale value (ADR-0052 point 7).
 *
 * Every resolution carries the [ToolApprovalReason] that produced it, so audit and UI show the
 * REAL state (ADR-0052 point 8): "the tool is unconfigured and keeps its original policy handling"
 * is not the same as "the user asked", and an ALLOW a contract change invalidated is not the same
 * as a fresh ASK.
 */
sealed interface ToolApprovalResolution {
    /**
     * Proceed without presenting a per-call confirmation card. Reached for a policy
     * [PolicyDecision.Allow] (an L0/L1 call inside the authorized scope) when the effective
     * preference is unset ([ToolApprovalReason.UNSET] — the original behavior) or an explicit
     * [ToolApprovalPreference.ALLOW] ([ToolApprovalReason.EXPLICIT]).
     */
    data class AutoProceed(
        val reason: ToolApprovalReason,
    ) : ToolApprovalResolution

    /** Present the per-call confirmation card and wait for the user's one-shot decision. */
    data class RequiresCard(
        val detail: String,
        val reason: ToolApprovalReason,
    ) : ToolApprovalResolution

    /**
     * Block the call and persist the settled denial. [code] is stable for audit and UI. A
     * preference never makes an unavailable tool usable: a policy denial always wins.
     */
    data class Blocked(
        val code: ToolApprovalBlockCode,
        val detail: String,
        val reason: ToolApprovalReason,
    ) : ToolApprovalResolution
}

/**
 * Why a preference-resolved call came out the way it did (ADR-0052 point 8; the 2026-09-14
 * clarification to point 1). Carried on every [ToolApprovalResolution] so the effective
 * preference's provenance is preserved instead of collapsed to a bare three-state value.
 */
enum class ToolApprovalReason {
    /** No live stored preference; the call keeps its original policy handling. */
    UNSET,

    /** The user explicitly stored ALLOW/ASK/DENY for this tool's trusted (source, name) identity. */
    EXPLICIT,

    /** A stored ALLOW no longer matches the current contractHash/source; it falls back to ASK. */
    ALLOW_INVALIDATED,

    /**
     * A trusted tool-registration/upgrade baseline marks this tool as newly added, so it defaults
     * to ASK (ADR-0052 point 1). Reserved: produced only once such a baseline exists — never
     * inferred from an empty preference record or a model's own claim.
     */
    NEW_DEFAULT,

    /** The card or block is driven by the policy decision itself, not by a user preference. */
    POLICY,
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
 * The collapsed effective preference for one tool call, WITH the provenance that produced it
 * (ADR-0052 2026-09-14 clarification). This replaces the bare three-state value so the runtime
 * can tell "nothing stored" (follow the original policy) from "an explicit ASK" (force a card)
 * from "an ALLOW a contract change invalidated" (fall back to ASK, but say so).
 */
sealed interface EffectiveToolPreference {
    /** No live stored preference; resolution passes through to the policy decision. */
    data object Unset : EffectiveToolPreference

    /** A live, contract-matching explicit ALLOW. */
    data object Allow : EffectiveToolPreference

    /**
     * An effective ASK. [reason] distinguishes an explicitly stored ASK ([ToolApprovalReason.EXPLICIT])
     * from an ALLOW a contract change invalidated ([ToolApprovalReason.ALLOW_INVALIDATED]) from the
     * new-tool default ([ToolApprovalReason.NEW_DEFAULT]).
     */
    data class Ask(
        val reason: ToolApprovalReason,
    ) : EffectiveToolPreference

    /** An explicit DENY in an applicable scope (authoritative; a narrower ALLOW/ASK never overrides it). */
    data object Deny : EffectiveToolPreference
}

/**
 * Resolves a user [ToolApprovalPreference] against a [PolicyDecision] into the effective handling
 * for one tool call (ADR-0052; 2026-09-14 clarification to point 1). Pure and stateless: it takes
 * the collapsed [EffectiveToolPreference] (with provenance) and the policy engine's decision, and
 * returns a [ToolApprovalResolution] the caller (Registry or Dispatcher) acts on.
 */
object ToolApprovalResolver {
    /**
     * Resolves the effective handling for one tool call.
     *
     * @param effective the collapsed effective preference for this call (with provenance).
     * @param decision the policy engine's decision for the same call.
     *
     * Priority (points 2-5, then the clarified point 1):
     * 1. A policy [PolicyDecision.Deny] always wins — a preference can never make an unavailable
     *    tool usable or expand a scope (points 2, 4).
     * 2. An effective [EffectiveToolPreference.Deny] blocks the call at the execution boundary
     *    (point 4).
     * 3. A policy [PolicyDecision.RequiresApproval] (L2/L3 or high-sensitivity egress) always
     *    presents a card, regardless of ALLOW — ALLOW does not mint a wildcard high-risk proof
     *    (point 2).
     * 4. A policy [PolicyDecision.Allow] (an in-scope L0/L1 call) proceeds card-free when the
     *    effective preference is [EffectiveToolPreference.Unset] (the original behavior — no
     *    preference stored) or an explicit [EffectiveToolPreference.Allow]; an effective ASK
     *    (explicit, an invalidated-ALLOW fallback, or the new-tool default) forces a card.
     */
    fun resolve(
        effective: EffectiveToolPreference,
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
                    reason = ToolApprovalReason.POLICY,
                )
            }

            // A stored DENY blocks at the execution boundary (point 4), overriding even a
            // high-risk card: the user said this tool must not run, so we do not merely ask.
            effective is EffectiveToolPreference.Deny -> {
                ToolApprovalResolution.Blocked(
                    code = ToolApprovalBlockCode.PREFERENCE_DENIED,
                    detail = "Tool blocked by the user's DENY preference.",
                    reason = ToolApprovalReason.EXPLICIT,
                )
            }

            // L2/L3 or high-sensitivity egress always presents a card. ALLOW does not grant a
            // wildcard high-risk proof (point 2), so this ignores the (non-DENY) preference.
            decision is PolicyDecision.RequiresApproval -> {
                ToolApprovalResolution.RequiresCard(
                    detail = decision.detail,
                    reason = ToolApprovalReason.POLICY,
                )
            }

            // An in-scope L0/L1 call the policy would auto-resolve. UNSET keeps the original
            // card-free behavior (nothing stored); an explicit ALLOW is card-free; an effective
            // ASK (explicit, invalidated-ALLOW fallback, or new-tool default) forces a card.
            else -> {
                when (effective) {
                    EffectiveToolPreference.Unset -> {
                        ToolApprovalResolution.AutoProceed(reason = ToolApprovalReason.UNSET)
                    }

                    EffectiveToolPreference.Allow -> {
                        ToolApprovalResolution.AutoProceed(reason = ToolApprovalReason.EXPLICIT)
                    }

                    is EffectiveToolPreference.Ask -> {
                        ToolApprovalResolution.RequiresCard(
                            detail = askDetail(effective.reason),
                            reason = effective.reason,
                        )
                    }

                    // Unreachable: a live DENY is handled above; kept for an exhaustive sealed when.
                    EffectiveToolPreference.Deny -> {
                        ToolApprovalResolution.Blocked(
                            code = ToolApprovalBlockCode.PREFERENCE_DENIED,
                            detail = "Tool blocked by the user's DENY preference.",
                            reason = ToolApprovalReason.EXPLICIT,
                        )
                    }
                }
            }
        }

    /**
     * Resolves model exposure for a tool (ADR-0052 point 4). Only a live DENY hides the tool;
     * UNSET, ALLOW and every ASK variant leave it visible, because ASK is a runtime restriction,
     * not a removal.
     */
    fun exposure(effective: EffectiveToolPreference): ToolApprovalExposure =
        if (effective is EffectiveToolPreference.Deny) {
            ToolApprovalExposure.HIDDEN_BY_DENY
        } else {
            ToolApprovalExposure.EXPOSE
        }

    /**
     * Collapses the stored records applicable to one tool call into the single effective preference
     * (ADR-0052 points 5, 6, 7; 2026-09-14 clarification).
     *
     * Rule order:
     * 1. A stored ALLOW that no longer matches the current descriptor [currentContractHash] is
     *    dropped first — it is no longer live after a contract or source change. A stored DENY or
     *    ASK never carries a contract binding, so it always stays live (point 6).
     * 2. A live DENY in any applicable scope is authoritative: a narrower ALLOW (or ASK) cannot
     *    override an outer DENY (point 5).
     * 3. Otherwise the narrowest present scope wins: SESSION over WORKSPACE over GLOBAL (point 5).
     * 4. When NO record is live but a stored ALLOW was dropped by step 1, the result is an
     *    effective ASK tagged [ToolApprovalReason.ALLOW_INVALIDATED] — distinct from a fresh
     *    [EffectiveToolPreference.Unset], so the runtime says "your ALLOW no longer applies"
     *    rather than "you never set anything" (point 6 + point 8).
     * 5. When nothing is stored at all, the result is [EffectiveToolPreference.Unset] and the call
     *    keeps its original policy handling (the clarified point 1: an existing unconfigured tool
     *    is not forced to ASK — a new-tool default to ASK needs a trusted registration/upgrade
     *    baseline, not an empty record).
     */
    fun effectivePreference(
        records: List<ToolApprovalPreferenceRecord>,
        currentContractHash: String?,
    ): EffectiveToolPreference {
        val live =
            records.filter {
                it.preference != ToolApprovalPreference.ALLOW ||
                    (it.contractHash != null && it.contractHash == currentContractHash)
            }
        // A live DENY in any applicable scope is authoritative (point 5) — the single early exit.
        if (live.any { it.preference == ToolApprovalPreference.DENY }) {
            return EffectiveToolPreference.Deny
        }
        val narrowest =
            live.firstOrNull { it.scope == ToolApprovalPreferenceScope.SESSION }
                ?: live.firstOrNull { it.scope == ToolApprovalPreferenceScope.WORKSPACE }
                ?: live.firstOrNull { it.scope == ToolApprovalPreferenceScope.GLOBAL }
        // The narrowest live scope wins (point 5); else a stored ALLOW a contract change dropped
        // falls back to an ASK tagged so the reason is preserved (point 6); else nothing was set.
        val narrowestEffective: EffectiveToolPreference? =
            narrowest?.let {
                when (it.preference) {
                    ToolApprovalPreference.ALLOW -> EffectiveToolPreference.Allow
                    ToolApprovalPreference.ASK -> EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT)
                    ToolApprovalPreference.DENY -> EffectiveToolPreference.Deny
                }
            }
        return narrowestEffective
            ?: if (records.any { it.preference == ToolApprovalPreference.ALLOW }) {
                EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED)
            } else {
                EffectiveToolPreference.Unset
            }
    }

    /** The card text for a preference-forced ASK, by provenance (point 8: show the real state). */
    private fun askDetail(reason: ToolApprovalReason): String =
        when (reason) {
            ToolApprovalReason.ALLOW_INVALIDATED -> {
                "The stored ALLOW no longer matches the tool contract; confirming before it runs."
            }

            ToolApprovalReason.NEW_DEFAULT -> {
                "This tool is newly added and not yet configured; confirming before it runs."
            }

            ToolApprovalReason.EXPLICIT,
            ToolApprovalReason.UNSET,
            ToolApprovalReason.POLICY,
            -> {
                "User preference asks before this tool runs."
            }
        }
}
