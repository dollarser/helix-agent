package com.helix.core.policy

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.ToolAvailabilityState

/**
 * The effect footprint of ONE tool call as the platform (not the model) classifies it
 * (ADR-PERMISSIONS-001 sections 1.2 and 3; HXA-209 Phase A execution-domain matrix).
 *
 * [effects] are the fully determined operation effects of the call: a copy carries the
 * source READ plus the target MUTATION, a move additionally the source mutation, a
 * download-save the network plus the target mutation. [undeterminedEffects] are effects the
 * execution domain CANNOT fully rule out: under a DENY they refuse the whole call (a DENY is
 * never downgraded to ASK), under an ASK they ask while explaining the undetermined effect.
 * An empty footprint (a pure metadata or computation call) skips the card when allowed.
 */
data class OperationFootprint(
    val effects: Set<OperationEffect> = emptySet(),
    val undeterminedEffects: Set<OperationEffect> = emptySet(),
) {
    init {
        require(effects.intersect(undeterminedEffects).isEmpty()) {
            "an effect is either determined or undetermined, not both"
        }
    }

    /** Every effect the resolver must consider, determined or not. */
    val all: Set<OperationEffect> get() = effects + undeterminedEffects
}

/**
 * Structured approval/denial reasons (ADR-PERMISSIONS-001 section 5): a closed set that the
 * same resolver produces both when presenting a card and when an execution starts — never
 * matched against description strings. Scope, risk, tool network, operation ASK, the rm
 * command rule and hard refusals are expressed separately.
 *
 * [SCOPE_OUTSIDE] and [RISK_LEVEL] are composed by the dispatcher (it knows the call scope
 * and the Policy Engine's dynamic risk); the pure resolver emits the effect-derived codes.
 */
enum class PermissionReasonCode {
    /** An outside-workspace / outside-read-scope effect. */
    SCOPE_OUTSIDE,

    /** The dynamic risk level, for display, audit and anomaly analysis; it never re-gates alone. */
    RISK_LEVEL,

    /** A tool network / remote business effect contributes to the approval. */
    TOOL_NETWORK,

    /** An operation category the mode resolves to ASK. */
    OPERATION_ASK,

    /** The execution domain cannot fully determine the effect; the card must say so. */
    UNDETERMINED_EFFECT,

    /** The explicit `rm -rf` command rule (ADR section 3); every mode needs the precise one-time approval. */
    RM_COMMAND_RULE,

    /** A hard refusal: a determined or undetermined effect touches a DENY, or a step-1 hard gate. */
    HARD_DENIAL,
}

/** One structured reason attached to a resolution; [effect] names the effect it came from. */
data class PermissionReason(
    val code: PermissionReasonCode,
    val effect: OperationEffect? = null,
)

/** Why a call is refused outright (no card, no proof consumption — ADR section 2). */
enum class PermissionDenyCode {
    /** Step-1 hard gate: legitimacy, real system capability, data integrity, budget or egress refusal. */
    HARD_POLICY,

    /** Step 2: the tool identity is DISABLED at an applicable scope; a session enable
     * cannot override an outer disable.
     */
    TOOL_DISABLED,

    /** A determined effect hits a DENY: any DENY refuses the whole call (ADR section 1.2). */
    OPERATION_DENIED,

    /**
     * An undetermined effect cannot be ruled clear of a DENY and the execution domain cannot
     * guarantee the restriction (ADR section 3): refuse with the concrete environment
     * reason, never downgrade to ASK.
     */
    OPERATION_DENIED_DOMAIN,
}

/** The terminal session-permission verdict for one call (ADR-PERMISSIONS-001 section 2). */
sealed interface SessionPermissionResolution {
    /** Every effect ALLOW and no rm-rule hit: no per-call confirmation card. */
    data object AutoProceed : SessionPermissionResolution

    /**
     * ONE precise approval for the whole call; [reasons] is the structured set shown on the
     * card and recorded in the audit. Never empty: at least one effect or the rm rule asked.
     */
    data class RequiresApproval(
        val reasons: Set<PermissionReason>,
    ) : SessionPermissionResolution {
        init {
            require(reasons.isNotEmpty()) { "an approval must carry at least one structured reason" }
        }
    }

    /** Refused outright; only a user settings change lifts it, never a single approval. */
    data class Denied(
        val code: PermissionDenyCode,
        val reasons: Set<PermissionReason>,
    ) : SessionPermissionResolution
}

/**
 * THE single session permission resolver (ADR-PERMISSIONS-001 section 2 step 4): the three
 * presets and CUSTOM share this one decision over one compiled [SessionPermissionConfig].
 * The dispatcher applies it AFTER the step-1 hard gates and the step-2 tool-availability
 * check, feeding the merged call footprint and the rm command rule outcome. It never
 * re-derives the risk gates (risk informs display/audit only) and never executes part of a
 * call before refusing the rest (ADR section 1.2).
 */
object SessionPermissionResolver {
    /** The outside-workspace / outside-read-scope keys, for the SCOPE_OUTSIDE reason. */
    private val EXTERNAL_EFFECTS =
        setOf(OperationEffect.FILE_READ_EXTERNAL, OperationEffect.FILE_MUTATION_EXTERNAL)

    /**
     * Merges ALL effects of the call, then applies the rm-rule floor (ADR sections 1.2, 2,
     * 3): any DENY refuses the whole call (an undetermined effect touching a DENY refuses
     * with the execution-domain reason); otherwise any ASK — or an rm-rule hit — yields ONE
     * precise approval; only an all-ALLOW footprint without an rm hit proceeds card-free.
     */
    fun resolve(
        config: SessionPermissionConfig,
        footprint: OperationFootprint,
        rmCommandHit: Boolean,
    ): SessionPermissionResolution {
        val reasons = linkedSetOf<PermissionReason>()
        var denial: PermissionDenyCode? = null
        for (effect in footprint.all) {
            val undetermined = effect in footprint.undeterminedEffects
            when (config.ruleFor(effect)) {
                OperationRule.DENY -> {
                    if (denial == null) {
                        denial = denialCode(undetermined)
                        addDenialReasons(reasons, effect, undetermined)
                    }
                }

                OperationRule.ASK -> {
                    reasons += askReason(effect, undetermined)
                }

                OperationRule.ALLOW -> {
                    Unit
                }
            }
        }
        if (denial != null) {
            return SessionPermissionResolution.Denied(checkNotNull(denial), reasons)
        }
        if (rmCommandHit) {
            reasons += PermissionReason(PermissionReasonCode.RM_COMMAND_RULE)
        }
        return if (reasons.isEmpty()) {
            SessionPermissionResolution.AutoProceed
        } else {
            SessionPermissionResolution.RequiresApproval(reasons)
        }
    }

    /** The denial code for the first effect that hits a DENY: a domain reason when undetermined. */
    private fun denialCode(undetermined: Boolean): PermissionDenyCode =
        if (undetermined) {
            PermissionDenyCode.OPERATION_DENIED_DOMAIN
        } else {
            PermissionDenyCode.OPERATION_DENIED
        }

    /** The structured reasons recorded on a denial for the effect that triggered it. */
    private fun addDenialReasons(
        reasons: MutableSet<PermissionReason>,
        effect: OperationEffect,
        undetermined: Boolean,
    ) {
        reasons += PermissionReason(PermissionReasonCode.HARD_DENIAL, effect)
        if (undetermined) {
            reasons += PermissionReason(PermissionReasonCode.UNDETERMINED_EFFECT, effect)
        }
    }

    /** The structured reason for one ASK-resolved effect. */
    private fun askReason(
        effect: OperationEffect,
        undetermined: Boolean,
    ): PermissionReason =
        when {
            undetermined -> {
                PermissionReason(PermissionReasonCode.UNDETERMINED_EFFECT, effect)
            }

            effect in EXTERNAL_EFFECTS -> {
                PermissionReason(PermissionReasonCode.SCOPE_OUTSIDE, effect)
            }

            effect == OperationEffect.REMOTE_BUSINESS_MUTATION -> {
                PermissionReason(PermissionReasonCode.TOOL_NETWORK, effect)
            }

            else -> {
                PermissionReason(PermissionReasonCode.OPERATION_ASK, effect)
            }
        }
}

/**
 * Effective tool availability across the GLOBAL < WORKSPACE < SESSION scopes
 * (ADR-PERMISSIONS-001 section 1.1): an OUTER disable always wins — a session ENABLED can
 * never silently override a global/workspace DISABLED — otherwise the narrowest explicit
 * state wins, defaulting to ENABLED. The execution entry and EVERY exposure surface (model
 * schema, tools.search, discovery) must use this one function so they cannot disagree.
 */
fun effectiveAvailability(
    global: ToolAvailabilityState? = null,
    workspace: ToolAvailabilityState? = null,
    session: ToolAvailabilityState? = null,
): ToolAvailabilityState {
    if (
        global == ToolAvailabilityState.DISABLED ||
        workspace == ToolAvailabilityState.DISABLED ||
        session == ToolAvailabilityState.DISABLED
    ) {
        return ToolAvailabilityState.DISABLED
    }
    return session ?: workspace ?: global ?: ToolAvailabilityState.ENABLED
}
